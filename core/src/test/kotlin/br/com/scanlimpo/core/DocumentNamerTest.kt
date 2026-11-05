package br.com.scanlimpo.core

import br.com.scanlimpo.core.text.DocumentNamer
import br.com.scanlimpo.core.text.OcrLineInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentNamerTest {
    /** Monta a página como o OCR entrega: cada linha com altura e posição. */
    private fun page(vararg rows: Triple<String, Float, Boolean>): List<OcrLineInfo> {
        var y = 0.06f
        return rows.map { (text, height, centered) ->
            val width = (text.length * height * 0.45f).coerceAtMost(0.84f)
            val x = if (centered) 0.5f - width / 2f else 0.08f
            OcrLineInfo(text, x, y, width, height).also { y += height + 0.012f }
        }
    }

    private fun title(text: String) = Triple(text, 0.03f, true)
    private fun body(text: String) = Triple(text, 0.016f, false)

    private fun best(vararg rows: Triple<String, Float, Boolean>) = DocumentNamer.suggest(page(*rows)).firstOrNull()

    @Test
    fun usesTheDocumentTitle() {
        assertEquals(
            "Declaração de residência",
            best(
                title("DECLARAÇÃO DE RESIDÊNCIA"),
                body("Eu, Maria Aparecida dos Santos, inscrita no CPF sob o nº 123.456.789-09, declaro para os"),
                body("devidos fins de comprovação, sob as penas da lei, que resido na Rua das Palmeiras."),
            ),
        )
    }

    @Test
    fun titleBeatsWordsMentionedInTheBody() {
        // O corpo fala em carteira de identidade e nota fiscal, mas o documento é um contrato.
        assertEquals(
            "Contrato de prestação de serviços",
            best(
                title("CONTRATO DE PRESTAÇÃO DE SERVIÇOS"),
                body("O contratante, portador da carteira de identidade nº 12.345.678, emitirá nota fiscal"),
                body("mensalmente, conforme a cláusula terceira deste instrumento."),
            ),
        )
    }

    @Test
    fun letterheadIsNotTheName() {
        assertEquals(
            "Certidão de nascimento",
            best(
                title("REPÚBLICA FEDERATIVA DO BRASIL"),
                title("REGISTRO CIVIL DAS PESSOAS NATURAIS"),
                title("CERTIDÃO DE NASCIMENTO"),
                body("Nome: João da Silva"),
            ),
        )
    }

    @Test
    fun recognizesIdDocumentsWithoutATitleWord() {
        assertEquals(
            "CNH",
            best(
                title("REPÚBLICA FEDERATIVA DO BRASIL"),
                body("MINISTÉRIO DA INFRAESTRUTURA"),
                body("CARTEIRA NACIONAL DE HABILITAÇÃO"),
                body("NOME MARIA DOS SANTOS"),
            ),
        )
        assertEquals(
            "RG",
            best(
                title("REPÚBLICA FEDERATIVA DO BRASIL"),
                body("ESTADO DE MINAS GERAIS"),
                body("CARTEIRA DE IDENTIDADE"),
                body("REGISTRO GERAL 12.345.678"),
            ),
        )
    }

    @Test
    fun recognizesBillsAndReceipts() {
        assertEquals(
            "Boleto",
            best(body("Banco do Brasil 001-9"), body("Linha digitável 00190.00009 01234.567890"), body("Vencimento 10/12/2026")),
        )
        assertEquals(
            "Nota fiscal",
            best(body("DANFE Documento Auxiliar da Nota Fiscal Eletrônica"), body("Valor total R$ 150,00")),
        )
        // Quando o próprio papel diz o que é, o título ganha do tipo genérico.
        assertEquals(
            "Comprovante de transferência",
            best(body("Banco Exemplo"), body("Comprovante de transferência"), body("Valor R$ 50,00")),
        )
        assertEquals(
            "Comprovante de Pix",
            best(body("Banco Exemplo"), body("Dados da transferência Pix realizada"), body("Valor R$ 50,00")),
        )
    }

    @Test
    fun fallsBackToTheMostProminentLine() {
        assertEquals(
            "Reunião do conselho de pais",
            best(title("REUNIÃO DO CONSELHO DE PAIS"), body("Ficou decidido que a próxima reunião será em dezembro.")),
        )
    }

    @Test
    fun keepsAcronymsAndNormalCase() {
        assertEquals("Comprovante do CPF", best(title("COMPROVANTE DO CPF"), body("Texto da página.")))
        assertEquals("Ata de reunião", best(title("Ata de reunião"), body("Texto da página em corpo menor.")))
    }

    @Test
    fun ignoresJunk() {
        val lines = page(
            body("12/05/2026"),
            body("www.exemplo.com.br"),
            body("Página 1 de 3"),
            body("contato@exemplo.com"),
            body("123.456.789-09"),
            body("xy"),
        )
        assertTrue("Lixo não pode virar nome: ${DocumentNamer.suggest(lines)}", DocumentNamer.suggest(lines).isEmpty())
        assertTrue(DocumentNamer.suggest(emptyList()).isEmpty())
    }

    @Test
    fun offersDifferentAlternatives() {
        val suggestions = DocumentNamer.suggest(
            page(title("RECIBO DE ALUGUEL"), title("IMOBILIÁRIA CENTRAL"), body("Recebi de João a quantia de R$ 900,00.")),
        )
        assertEquals("Recibo de aluguel", suggestions.first())
        assertTrue("Devia oferecer alternativas: $suggestions", suggestions.size >= 2)
        assertEquals("Sem repetição", suggestions.size, suggestions.distinct().size)
    }

    @Test
    fun automaticNameOnlyWhenConfident() {
        // Página de caderno escrita à mão: o OCR devolve pedaços soltos. Fica o nome padrão, com a data.
        val handwriting = page(body("Asto"), body("nia de 12"), body("pus a mo"), body("corta lu"), body("Rd 12 x"))
        assertEquals(null, DocumentNamer.automaticName(handwriting))
        assertTrue("Ao renomear ainda dá para escolher", DocumentNamer.suggest(handwriting).isNotEmpty())

        // Parágrafo comum, sem título nenhum, também não vira nome.
        val paragraph = page(body("ficou combinado que a entrega será feita na próxima semana"), body("sem falta e com nota"))
        assertEquals(null, DocumentNamer.automaticName(paragraph))

        // Tudo que tem cara de documento continua sendo nomeado.
        assertEquals("Declaração de residência", DocumentNamer.automaticName(page(title("DECLARAÇÃO DE RESIDÊNCIA"), body("Eu, Maria, declaro."))))
        assertEquals("Relatório", DocumentNamer.automaticName(page(title("RELATÓRIO"), body("Texto da página."))))
        assertEquals("CNH", DocumentNamer.automaticName(page(body("MINISTÉRIO"), body("CARTEIRA NACIONAL DE HABILITAÇÃO"))))
        assertEquals("Reunião do conselho de pais", DocumentNamer.automaticName(page(title("REUNIÃO DO CONSELHO DE PAIS"), body("Texto."))))
        assertEquals(null, DocumentNamer.automaticName(emptyList()))
    }

    @Test
    fun worksWithPlainText() {
        assertEquals(
            "Declaração de residência",
            DocumentNamer.suggestFromText("DECLARAÇÃO DE RESIDÊNCIA\nEu, Maria, declaro para os devidos fins.").firstOrNull(),
        )
    }
}
