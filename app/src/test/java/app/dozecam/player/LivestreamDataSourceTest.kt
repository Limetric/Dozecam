package app.dozecam.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LivestreamDataSourceTest {

    private val uri: Uri = Uri.parse("dozecam://livestream")

    @Test
    fun `reports an unknown length because the stream never ends on its own`() {
        val source = LivestreamDataSource(LivestreamPipe())

        val length = source.open(DataSpec(uri))

        assertEquals(C.LENGTH_UNSET.toLong(), length)
        assertEquals(uri, source.uri)
    }

    @Test
    fun `serves the bytes the pipe was given`() {
        val pipe = LivestreamPipe()
        pipe.offer("moof".encodeToByteArray())
        val source = LivestreamDataSource(pipe)
        source.open(DataSpec(uri))

        val buffer = ByteArray(16)
        val count = source.read(buffer, 0, buffer.size)

        assertEquals("moof", buffer.decodeToString(0, count))
    }

    @Test
    fun `passes end of input through to the extractor`() {
        val pipe = LivestreamPipe()
        pipe.finish()
        val source = LivestreamDataSource(pipe)
        source.open(DataSpec(uri))

        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(16), 0, 16))
    }

    @Test
    fun `forgets the uri once closed`() {
        val source = LivestreamDataSource(LivestreamPipe())
        source.open(DataSpec(uri))

        source.close()

        assertEquals(null, source.uri)
    }
}
