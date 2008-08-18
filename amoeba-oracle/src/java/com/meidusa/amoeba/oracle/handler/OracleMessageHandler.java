package com.meidusa.amoeba.oracle.handler;

import org.apache.log4j.Logger;

import com.meidusa.amoeba.net.Connection;
import com.meidusa.amoeba.net.MessageHandler;
import com.meidusa.amoeba.net.Sessionable;
import com.meidusa.amoeba.oracle.packet.AnoClientDataPacket;
import com.meidusa.amoeba.oracle.packet.AnoPacketBuffer;
import com.meidusa.amoeba.oracle.packet.AnoServices;
import com.meidusa.amoeba.oracle.packet.SQLnetDef;
import com.meidusa.amoeba.oracle.packet.T4C8TTIproDataPacket;
import com.meidusa.amoeba.oracle.packet.T4C8TTIproResponseDataPacket;

/**
 * �ǳ��򵥵����ݰ�ת������
 * 
 * @author struct
 */
public class OracleMessageHandler implements MessageHandler, Sessionable, SQLnetDef {

    private static Logger  logger         = Logger.getLogger(OracleMessageHandler.class);

    private Connection     clientConn;
    private Connection     serverConn;
    private MessageHandler clientHandler;
    private MessageHandler serverHandler;
    private boolean        isEnded        = false;

    private int            serverMsgCount = 0;
    private int            clientMsgCount = 0;

    public OracleMessageHandler(Connection clientConn, Connection serverConn){
        this.clientConn = clientConn;
        clientHandler = clientConn.getMessageHandler();
        this.serverConn = serverConn;
        serverHandler = serverConn.getMessageHandler();
        clientConn.setMessageHandler(this);
        serverConn.setMessageHandler(this);
    }

    public void handleMessage(Connection conn, byte[] message) {
        if (conn == clientConn) {
            clientMsgCount++;

            switch (message[4]) {
                case NS_PACKT_TYPE_CONNECT:
                    message[32] = (byte) NSINADISABLEFORCONNECTION;
                    message[33] = (byte) NSINADISABLEFORCONNECTION;
                    break;
                case NS_PACKT_TYPE_DATA:
                    if (clientMsgCount == 3) {
                        AnoPacketBuffer buffer = new AnoPacketBuffer(message);
                        buffer.setPosition(10);
                        if (buffer.readUB4() == AnoServices.NA_MAGIC) {
                            AnoClientDataPacket packet = new AnoClientDataPacket();
                            packet.anoServiceSize = 0;
                            serverMsgCount++;
                            clientConn.postMessage(packet.toByteBuffer().array());
                            return;
                        }
                    }
                    if (clientMsgCount == 4) {
                        T4C8TTIproDataPacket packet = new T4C8TTIproDataPacket();
                        message = packet.toByteBuffer().array();
                    }
                    break;
            }

            serverConn.postMessage(message);// proxy-->server
        } else {
            serverMsgCount++;

            switch (message[4]) {
                case NS_PACKT_TYPE_DATA:
                    if (clientMsgCount == 4) {
                        T4C8TTIproResponseDataPacket packet = new T4C8TTIproResponseDataPacket();
                        message = packet.toByteBuffer().array();
                    }
                    break;
            }

            clientConn.postMessage(message);// proxy-->client
        }
    }

    /**
     *�����ͻ��˷��͵����ݰ�
     */
    @SuppressWarnings("unused")
    private void parseClientPacket(int count, byte[] msg) {
    }

    public boolean checkIdle(long now) {
        return false;
    }

    public synchronized void endSession() {
        if (!isEnded()) {
            isEnded = true;
            clientConn.setMessageHandler(clientHandler);
            serverConn.setMessageHandler(serverHandler);
            clientConn.postClose(null);
            serverConn.postClose(null);
        }
    }

    public boolean isEnded() {
        return isEnded;
    }

    public void startSession() throws Exception {
    }

}