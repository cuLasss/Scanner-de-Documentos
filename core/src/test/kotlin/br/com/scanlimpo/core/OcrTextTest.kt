package br.com.scanlimpo.core

import br.com.scanlimpo.core.text.OcrText
import br.com.scanlimpo.core.text.OcrBlockInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrTextTest {
    @Test
    fun joinsLinesAndHyphenatedWords() {
        val text = "Este é um docu-\nmento de\nteste\n\nSegundo bloco\n  com espaços  \n\n\n"
        assertEquals(listOf("Este é um documento de teste", "Segundo bloco com espaços"), OcrText.paragraphs(text))
    }

    @Test
    fun keepsHyphenBeforeUppercase() {
        assertEquals(listOf("Rio-\nGrande".replace("\n", " ")), OcrText.paragraphs("Rio-\nGrande"))
    }

    @Test
    fun arrangesBlocksInReadingOrder() {
        // Na ordem em que o reconhecimento costuma entregar: corpo, título e tabela por colunas.
        val blocks = listOf(
            OcrBlockInfo(listOf("Primeiro parágrafo", "com duas linhas."), 0.1f, 0.15f, 0.8f, 0.05f),
            OcrBlockInfo(listOf("DECLARAÇÃO"), 0.3f, 0.05f, 0.4f, 0.03f),
            OcrBlockInfo(listOf("Item"), 0.1f, 0.30f, 0.2f, 0.02f),
            OcrBlockInfo(listOf("Café"), 0.1f, 0.34f, 0.2f, 0.02f),
            OcrBlockInfo(listOf("Valor"), 0.6f, 0.302f, 0.2f, 0.02f),
            OcrBlockInfo(listOf("R$ 5"), 0.6f, 0.341f, 0.2f, 0.02f),
            OcrBlockInfo(listOf("Assinatura"), 0.1f, 0.8f, 0.3f, 0.02f),
        )
        assertEquals(
            "DECLARAÇÃO\n\nPrimeiro parágrafo\ncom duas linhas.\n\nItem    Valor\n\nCafé    R$ 5\n\nAssinatura",
            OcrText.arrange(blocks),
        )
    }

    @Test
    fun keepsSideBySideParagraphsSeparate() {
        val blocks = listOf(
            OcrBlockInfo(listOf("Coluna direita", "segunda linha"), 0.55f, 0.1f, 0.35f, 0.1f),
            OcrBlockInfo(listOf("Coluna esquerda", "segunda linha"), 0.05f, 0.1f, 0.35f, 0.1f),
        )
        assertEquals(
            "Coluna esquerda\nsegunda linha\n\nColuna direita\nsegunda linha",
            OcrText.arrange(blocks),
        )
        assertEquals("", OcrText.arrange(emptyList()))
    }

    @Test
    fun searchIgnoresCaseAndAccents() {
        assertEquals("declaracao de acao", OcrText.searchKey("DECLARAÇÃO de Ação"))
        assertTrue(OcrText.searchKey("Coração de São João").contains(OcrText.searchKey("sao joao")))
    }

    @Test
    fun snippetFindsAccentedTextFromPlainQuery() {
        val text = "Linha inicial\nContrato de prestação de serviços gerais\nLinha final"
        val snippet = OcrText.snippet(text, "prestacao", radius = 8)!!
        assertTrue(snippet, snippet.contains("prestação"))
        assertTrue(snippet, snippet.startsWith("…") && snippet.endsWith("…"))
        assertNull(OcrText.snippet(text, "inexistente"))
    }
}
