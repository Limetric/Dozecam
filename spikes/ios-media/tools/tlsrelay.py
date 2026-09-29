#!/usr/bin/env python3
"""RTSP over TLS with plain interleaved RTP, the way Protect consoles serve
rtsps:// on 7441: terminates TLS on :18323 with a self-signed certificate and
relays bytes to the plain testbed on :18554. (mediamtx's own RTSPS insists on
SRTP, which VLC's live555 cannot play and Protect does not use.)

Usage: tlsrelay.py CERT KEY
"""
import asyncio
import ssl
import sys


async def pipe(reader, writer):
    try:
        while data := await reader.read(65536):
            writer.write(data)
            await writer.drain()
    except (ConnectionError, asyncio.CancelledError):
        pass
    finally:
        writer.close()


async def handle(client_reader, client_writer):
    try:
        server_reader, server_writer = await asyncio.open_connection("127.0.0.1", 18554)
    except OSError:
        client_writer.close()
        return
    await asyncio.gather(pipe(client_reader, server_writer), pipe(server_reader, client_writer))


async def main():
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(sys.argv[1], sys.argv[2])
    server = await asyncio.start_server(handle, "0.0.0.0", 18323, ssl=context)
    async with server:
        await server.serve_forever()


asyncio.run(main())
