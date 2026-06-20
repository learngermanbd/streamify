package com.streamify.app.data.local

import com.google.common.truth.Truth.assertThat
import com.streamify.app.data.models.StreamLink
import com.streamify.app.data.models.VideoQuality
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ConvertersTest {

    private val converters = Converters()

    @Test
    fun `streamLinkList - round-trip empty list`() {
        val items = emptyList<StreamLink>()
        val json = converters.streamLinkListToJson(items)
        val restored = converters.jsonToStreamLinkList(json)
        assertThat(restored).isEmpty()
    }

    @Test
    fun `streamLinkList - round-trip single item`() {
        val link = StreamLink(name = "HD Stream", url = "https://example.com/stream.m3u8", quality = VideoQuality.HD)
        val json = converters.streamLinkListToJson(listOf(link))
        val restored = converters.jsonToStreamLinkList(json)

        assertThat(restored.size).isEqualTo(1)
        assertThat(restored[0].name).isEqualTo("HD Stream")
        assertThat(restored[0].url).isEqualTo("https://example.com/stream.m3u8")
    }

    @Test
    fun `streamLinkList - round-trip multiple items`() {
        val items = listOf(
            StreamLink(name = "A", url = "http://a", quality = VideoQuality.HD),
            StreamLink(name = "B", url = "http://b", quality = VideoQuality.SD)
        )
        val json = converters.streamLinkListToJson(items)
        val restored = converters.jsonToStreamLinkList(json)

        assertThat(restored.size).isEqualTo(2)
        assertThat(restored[0].name).isEqualTo("A")
        assertThat(restored[1].name).isEqualTo("B")
    }

    @Test
    fun `streamLinkList - null json returns empty`() {
        assertThat(converters.jsonToStreamLinkList(null)).isEmpty()
    }

    @Test
    fun `streamLinkList - empty string json returns empty`() {
        assertThat(converters.jsonToStreamLinkList("")).isEmpty()
    }

    @Test
    fun `streamLinkList - malformed json throws`() {
        assertThrows<Exception> {
            converters.jsonToStreamLinkList("   ")
        }
    }

    @Test
    fun `stringList - round-trip`() {
        val items = listOf("alpha", "beta", "gamma")
        val json = converters.stringListToJson(items)
        val restored = converters.jsonToStringList(json)
        assertThat(restored.size).isEqualTo(3)
    }

    @Test
    fun `stringList - null json returns empty`() {
        assertThat(converters.jsonToStringList(null)).isEmpty()
    }

    @Test
    fun `stringList - empty json returns empty`() {
        assertThat(converters.jsonToStringList("")).isEmpty()
    }

    @Test
    fun `stringList - malformed json throws`() {
        assertThrows<Exception> {
            converters.jsonToStringList("   ")
        }
    }

    @Test
    fun `stringList - empty list round-trip`() {
        val items = emptyList<String>()
        val json = converters.stringListToJson(items)
        assertThat(converters.jsonToStringList(json)).isEmpty()
    }
}
