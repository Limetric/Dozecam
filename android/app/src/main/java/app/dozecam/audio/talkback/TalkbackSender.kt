package app.dozecam.audio.talkback

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * The socket half of talk-back: one datagram per Opus frame, to the camera.
 *
 * Nothing here can tell whether any of it arrived. UDP reports success for a
 * datagram sent to a host on an unreachable VLAN, there is no acknowledgement
 * to wait for, and the camera says nothing back — which is why reachability is
 * established with a TCP probe before the control is offered, and why a
 * "talking" state can only ever claim that the phone is speaking.
 */
class TalkbackSender(
    host: String,
    private val port: Int,
    private val packetiser: RtpPacketiser,
) : Closeable, FrameSink {

    private val address: InetAddress = InetAddress.getByName(host)
    private val socket = DatagramSocket()

    /** Sends one encoded frame. */
    override fun send(opusFrame: ByteArray) {
        val packet = packetiser.packetise(opusFrame)
        socket.send(DatagramPacket(packet, packet.size, address, port))
        bytesSent += packet.size
    }

    /** Total bytes handed to the socket, for logging. Not proof of arrival. */
    var bytesSent: Int = 0
        private set

    override fun close() {
        socket.close()
    }
}
