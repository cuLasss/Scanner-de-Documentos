package br.com.scanlimpo.core.text

import kotlin.math.abs

/** Linha reconhecida pelo OCR, com posição normalizada (0..1) na página. */
class OcrLineInfo(val text: String, val x: Float, val y: Float, val width: Float, val height: Float)

/**
 * Descobre o nome do documento a partir do texto lido na página.
 *
 * Roda inteiro no aparelho: regras sobre o que está escrito e sobre como está escrito.
 * Título costuma ser a maior linha da parte de cima da folha, e cada tipo de documento
 * brasileiro tem expressões que quase só aparecem nele.
 */
object DocumentNamer {
    private const val MAX_LENGTH = 60

    /** Quando uma destas palavras abre uma linha de destaque, essa linha é o nome do documento. */
    private val TITLE_WORDS = listOf(
        "declaracao", "contrato", "recibo", "atestado", "certidao", "procuracao", "requerimento",
        "termo", "ata", "edital", "oficio", "relatorio", "laudo", "exame", "resultado", "ficha",
        "formulario", "autorizacao", "comprovante", "certificado", "diploma", "orcamento", "fatura",
        "boleto", "nota", "historico", "curriculo", "curriculum", "receita", "receituario", "memorando",
        "portaria", "aviso", "notificacao", "solicitacao", "pedido", "proposta", "extrato", "apolice",
        "matricula", "escritura", "carta", "lista", "manual", "guia", "prontuario", "encaminhamento",
    )

    /** Tipos reconhecidos pelo conteúdo, para quando não há um título claro na página. */
    private val KNOWN_TYPES = listOf(
        Regex("carteira nacional de habilitacao|permissao para dirigir") to "CNH",
        Regex("carteira de identidade|cedula de identidade|registro geral") to "RG",
        Regex("carteira de trabalho") to "Carteira de trabalho",
        Regex("titulo (de )?eleitor|justica eleitoral") to "Título de eleitor",
        Regex("cadastro de pessoas? fisicas?") to "CPF",
        Regex("certidao de nascimento|registro de nascimento") to "Certidão de nascimento",
        Regex("certidao de casamento") to "Certidão de casamento",
        Regex("certidao de obito") to "Certidão de óbito",
        Regex("danfe|nota fiscal( eletronica)?|nf-?e\\b") to "Nota fiscal",
        Regex("cupom fiscal|nfc-?e\\b") to "Cupom fiscal",
        Regex("linha digitavel|ficha de compensacao|recibo do pagador|recibo do sacado") to "Boleto",
        Regex("comprovante de (transferencia|pix)|transferencia pix") to "Comprovante de Pix",
        Regex("comprovante de pagamento") to "Comprovante de pagamento",
        Regex("demonstrativo de pagamento|contra-?cheque|holerite|recibo de pagamento de salario") to "Contracheque",
        Regex("(conta|fatura) de (luz|energia)|energia eletrica|\\bkwh\\b") to "Conta de luz",
        Regex("(conta|fatura) de agua|abastecimento de agua") to "Conta de água",
        Regex("curriculum vitae|curriculo") to "Currículo",
        Regex("historico escolar") to "Histórico escolar",
        Regex("receituario|receita medica|prescricao medica") to "Receita médica",
        Regex("atestado medico") to "Atestado médico",
    )

    /** Cabeçalhos de instituição: aparecem grandes, mas não dizem o que o documento é. */
    private val LETTERHEAD = Regex(
        "^(republica federativa|governo d|prefeitura|ministerio|secretaria|poder judiciario|tribunal|" +
            "estado d|municipio d|universidade|instituto|faculdade|escola|banco|cartorio|sistema unico)",
    )
    private val JUNK = Regex("^(pagina|page|fls?\\b|data\\b|nome\\b|cpf\\b|rg\\b|cnpj\\b|tel\\b|telefone\\b|e-?mail\\b|cep\\b|endereco\\b)")
    private val WEB = Regex("www\\.|https?:|@|\\.com\\b|\\.br\\b")

    private val ACRONYMS = setOf(
        "CNH", "RG", "CPF", "CNPJ", "PIX", "INSS", "FGTS", "IPTU", "IPVA", "DARF", "DAS", "MEI", "CTPS", "SUS", "ENEM", "NF", "NFE", "DANFE", "CLT",
    )
    private val LOWER_WORDS = setOf("a", "o", "as", "os", "de", "da", "do", "das", "dos", "e", "em", "na", "no", "nas", "nos", "para", "por", "com")

    /** As melhores sugestões primeiro. Vazio quando não dá para dizer nada. */
    fun suggest(lines: List<OcrLineInfo>, limit: Int = 3): List<String> {
        val candidates = lines.mapNotNull(::candidate)
        val results = ArrayList<String>()

        // 1. Linha de destaque que já diz o que é: "Declaração de residência", "Contrato de aluguel".
        titleLine(candidates)?.let { results += format(it.text) }

        // 2. Tipo reconhecido pelo conteúdo: RG, CNH, nota fiscal, boleto...
        val folded = lines.joinToString(" ") { fold(it.text) }
        KNOWN_TYPES.firstOrNull { (pattern, _) -> pattern.containsMatchIn(folded) }?.let { results += it.second }

        // 3. Linhas com mais cara de título, de cima para baixo em ordem de destaque.
        candidates.sortedByDescending(::prominence).forEach { results += format(it.text) }

        return results.map { it.take(MAX_LENGTH).trim() }.filter { it.isNotBlank() }.distinctBy(::fold).take(limit)
    }

    /**
     * O nome para dar sozinho, ou null se não houver confiança. Papel escrito à mão, foto de tela e afins
     * viram palavras soltas no OCR, e um nome errado é pior que o nome padrão com a data.
     * Quem quiser ainda escolhe entre as sugestões ao renomear.
     */
    fun automaticName(lines: List<OcrLineInfo>): String? {
        val candidates = lines.mapNotNull(::candidate)
        titleLine(candidates)?.let { return format(it.text).take(MAX_LENGTH).trim() }

        val folded = lines.joinToString(" ") { fold(it.text) }
        KNOWN_TYPES.firstOrNull { (pattern, _) -> pattern.containsMatchIn(folded) }?.let { return it.second }

        if (candidates.isEmpty()) return null
        val typical = candidates.map { it.height }.sorted()[candidates.size / 2]
        return candidates
            .filter { isClearHeading(it, typical) }
            .maxByOrNull(::prominence)
            ?.let { format(it.text).take(MAX_LENGTH).trim() }
    }

    /** Cabeçalho claro: no alto da folha, com pelo menos duas palavras de verdade e algum destaque visual. */
    private fun isClearHeading(line: OcrLineInfo, typicalHeight: Float): Boolean {
        val words = line.text.split(' ').filter { word -> word.count(Char::isLetter) >= 3 }
        val standsOut = isUpperCase(line.text) || isCentered(line) || line.height >= typicalHeight * 1.3f
        return line.y < 0.4f && words.size >= 2 && words.sumOf { it.length } >= 10 && standsOut &&
            !LETTERHEAD.containsMatchIn(fold(line.text))
    }

    private fun plainTextLines(text: String): List<OcrLineInfo> {
        val rows = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        return rows.mapIndexed { i, row -> OcrLineInfo(row, 0.1f, i.toFloat() / maxOf(rows.size, 20), 0.8f, 0.02f) }
    }

    /** Para quando só há o texto corrido, sem a posição das linhas. */
    fun suggestFromText(text: String, limit: Int = 3): List<String> = suggest(plainTextLines(text), limit)

    fun automaticNameFromText(text: String): String? = automaticName(plainTextLines(text))

    private fun candidate(line: OcrLineInfo): OcrLineInfo? {
        val text = line.text.trim().trimEnd(':', '.', ',', ';', '-', '_').replace(Regex("\\s+"), " ").trim()
        if (text.length !in 4..80) return null
        if (text.count(Char::isLetter) < text.length * 0.6f) return null
        val folded = fold(text)
        if (WEB.containsMatchIn(folded) || JUNK.containsMatchIn(folded)) return null
        return OcrLineInfo(text, line.x, line.y, line.width, line.height)
    }

    private fun titleLine(candidates: List<OcrLineInfo>): OcrLineInfo? {
        if (candidates.isEmpty()) return null
        val typical = candidates.map { it.height }.sorted()[candidates.size / 2]
        return candidates
            .filter { it.y < 0.45f && startsWithTitleWord(it.text) }
            .filter { it.height >= typical * 0.95f && it.text.length <= MAX_LENGTH }
            // Dentro de um parágrafo a palavra pode abrir uma linha por acaso. Título é curto e costuma ser centralizado ou em maiúsculas.
            .filter { it.text.length <= 45 || isUpperCase(it.text) || isCentered(it) }
            .maxByOrNull(::prominence)
    }

    private fun startsWithTitleWord(text: String): Boolean {
        val first = fold(text).substringBefore(' ')
        return first in TITLE_WORDS
    }

    /** Altura pesa mais, e subir na folha, estar centralizado ou em maiúsculas ajudam. */
    private fun prominence(line: OcrLineInfo): Float {
        var score = line.height * 100f
        score *= (1.6f - line.y.coerceIn(0f, 1f))
        if (isUpperCase(line.text)) score *= 1.3f
        if (isCentered(line)) score *= 1.25f
        if (LETTERHEAD.containsMatchIn(fold(line.text))) score *= 0.25f
        if (line.text.length > 50) score *= 0.6f
        return score
    }

    private fun isCentered(line: OcrLineInfo): Boolean =
        abs(line.x + line.width / 2f - 0.5f) < 0.06f && line.width < 0.85f

    private fun isUpperCase(text: String): Boolean {
        val letters = text.filter(Char::isLetter)
        return letters.isNotEmpty() && letters.count(Char::isUpperCase) >= letters.length * 0.8f
    }

    /** "DECLARAÇÃO DE RESIDÊNCIA DO CPF" vira "Declaração de residência do CPF". */
    private fun format(text: String): String {
        val mostlyUpper = isUpperCase(text)
        val words = text.split(' ')
        return words.mapIndexed { index, word ->
            val bare = word.trim { !it.isLetterOrDigit() }
            when {
                bare.uppercase() in ACRONYMS -> word.replace(bare, bare.uppercase())
                !mostlyUpper -> word
                index > 0 && bare.lowercase() in LOWER_WORDS -> word.lowercase()
                index == 0 -> word.lowercase().replaceFirstChar(Char::uppercase)
                else -> word.lowercase()
            }
        }.joinToString(" ").replaceFirstChar(Char::uppercase)
    }

    private fun fold(text: String): String = OcrText.searchKey(text)
}
