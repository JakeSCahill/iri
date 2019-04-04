package com.iota.iri.network.neighbor.impl;

import com.iota.iri.network.neighbor.Neighbor;
import com.iota.iri.network.neighbor.NeighborMetrics;
import com.iota.iri.network.neighbor.NeighborState;
import com.iota.iri.network.pipeline.PreProcessPayload;
import com.iota.iri.network.pipeline.ProcessingContext;
import com.iota.iri.network.pipeline.TxPipeline;
import com.iota.iri.network.protocol.*;
import com.iota.iri.network.protocol.message.MessageReader;
import com.iota.iri.network.protocol.message.MessageReaderFactory;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class NeighborImpl<T extends SelectableChannel & ByteChannel> implements Neighbor {

    private static final Logger log = LoggerFactory.getLogger(NeighborImpl.class);

    private enum ReadState {
        PARSE_HEADER, READ_MESSAGE
    }

    // next stage in the processing of incoming data
    private TxPipeline txPipeline;

    // data to be written out to the neighbor
    private BlockingQueue<ByteBuffer> sendQueue = new LinkedBlockingQueue<>();
    private ByteBuffer currentToWrite;

    // stats
    private long msgsWritten;
    private long msgsRead;
    private long faultyPackets;

    private NeighborState state = NeighborState.HANDSHAKING;
    private ReadState readState = ReadState.PARSE_HEADER;

    // ident
    private String hostAddress;
    private int remoteServerSocketPort;

    // we need the reference to the channel in order to register it for
    // write interests once messages to send are available.
    private T channel;
    private Selector selector;

    private NeighborMetrics metrics = new NeighborMetricsImpl();
    private MessageReader msgReader;
    private Handshake handshake = new Handshake();

    public NeighborImpl(Selector selector, T channel, String hostAddress, int remoteServerSocketPort,
            TxPipeline txPipeline) {
        this.hostAddress = hostAddress;
        this.remoteServerSocketPort = remoteServerSocketPort;
        this.selector = selector;
        this.channel = channel;
        this.txPipeline = txPipeline;
        this.msgReader = MessageReaderFactory.create(Protocol.MessageType.HEADER,
                Protocol.MessageSize.HEADER.getSize());
    }

    @Override
    public Handshake handshake() throws IOException {
        if (read() == -1) {
            handshake.setState(Handshake.State.FAILED);
        }
        return handshake;
    }

    @Override
    public int read() throws IOException {
        int bytesRead = msgReader.readMessage(channel);
        if (!msgReader.ready()) {
            return bytesRead;
        }
        ByteBuffer msg = msgReader.getMessage();
        msg.flip();
        switch (readState) {

            case PARSE_HEADER:
                ProtocolHeader protocolHeader;
                try {
                    protocolHeader = Protocol.parseHeader(msg);
                } catch (UnknownMessageTypeException e) {
                    log.error("unknown message type received from {}", getHostAddressAndPort());
                    metrics.incrUnknownMessageTypePacketsCount();
                    return bytesRead;
                } catch (IncompatibleProtocolVersionException e) {
                    log.error("{} is incompatible due to protocol version mismatch", getHostAddressAndPort());
                    metrics.incrIncompatiblePacketsCount();
                    return bytesRead;
                } catch (AdvertisedMessageSizeTooBigException e) {
                    log.error("{} is trying to send a message which exceeds the max size of the given message type",
                            getHostAddressAndPort());
                    metrics.incrMessageTooBigPacketsCount();
                    return bytesRead;
                }

                // if we are handshaking, then we must have a handshaking packet
                if (state == NeighborState.HANDSHAKING
                        && protocolHeader.getMessageType() != Protocol.MessageType.HANDSHAKE) {
                    log.error("neighbor {}'s initial packet is not a handshaking packet, closing connection",
                            getHostAddressAndPort());
                    return -1;
                }

                // we got the header, now we want to read the message
                readState = ReadState.READ_MESSAGE;
                msgReader = MessageReaderFactory.create(protocolHeader.getMessageType(),
                        protocolHeader.getMessageSize());

                // execute another read as we likely already have the message in the network buffer
                return read();

            case READ_MESSAGE:
                switch (msgReader.getMessageType()) {
                    case HANDSHAKE:
                        handshake.setServerSocketPort((int) msg.getChar());
                        handshake.setSentTimestamp(msg.getLong());
                        handshake.setState(Handshake.State.OK);
                        break;
                    case TRANSACTION_GOSSIP:
                        msgsRead++;
                        txPipeline.process(this, msg);
                        break;
                }
                // reset
                readState = ReadState.PARSE_HEADER;
                try {
                    msgReader = MessageReaderFactory.create(Protocol.MessageType.HEADER);
                } catch (UnknownMessageTypeException e) {
                    // can't happen
                }
                return bytesRead;
        }
        return bytesRead;
    }

    @Override
    public int write() throws IOException {
        // previous message wasn't fully sent yet
        if (currentToWrite != null) {
            return writeMsg();
        }

        currentToWrite = sendQueue.poll();
        if (currentToWrite == null) {
            return 0;
        }
        return writeMsg();
    }

    private int writeMsg() throws IOException {
        int written = channel.write(currentToWrite);
        if (!currentToWrite.hasRemaining()) {
            msgsWritten++;
            currentToWrite = null;
        }
        return written;
    }

    @Override
    public void send(ByteBuffer buf) {
        try {
            // re-register write interest
            SelectionKey key = channel.keyFor(selector);
            if (key != null && key.isValid() && (key.interestOps() & SelectionKey.OP_WRITE) == 0) {
                key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                selector.wakeup();
            }

            sendQueue.put(buf);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public String getHostAddressAndPort() {
        if (remoteServerSocketPort == Neighbor.UNKNOWN_REMOTE_SERVER_SOCKET_PORT) {
            return hostAddress;
        }
        return String.format("%s:%d", hostAddress, remoteServerSocketPort);
    }

    @Override
    public String getHostAddress() {
        return hostAddress;
    }

    @Override
    public int getRemoteServerSocketPort() {
        return remoteServerSocketPort;
    }

    @Override
    public void setRemoteServerSocketPort(int port) {
        remoteServerSocketPort = port;
    }

    @Override
    public NeighborState getState() {
        return state;
    }

    @Override
    public void setState(NeighborState state) {
        if (this.state == NeighborState.MARKED_FOR_DISCONNECT) {
            return;
        }
        this.state = state;
    }

    @Override
    public NeighborMetrics getMetrics() {
        return metrics;
    }

    public long getMsgsWritten() {
        return msgsWritten;
    }

    public long getMsgsRead() {
        return msgsRead;
    }
}
