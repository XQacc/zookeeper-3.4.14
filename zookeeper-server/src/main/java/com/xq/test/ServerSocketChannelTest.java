package com.xq.test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

public class ServerSocketChannelTest {
    static Selector selector;

    static {
        try {
            selector = Selector.open();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        ServerSocketChannel ss;
        ByteBuffer byteBuffer = ByteBuffer.allocate(1024);
        try {
            ss= ServerSocketChannel.open();
            ss.socket().setReuseAddress(true);
            System.out.println("binding to port 2288");
            ss.socket().bind(new InetSocketAddress(2288));
            ss.configureBlocking(false);
            ss.register(selector, SelectionKey.OP_ACCEPT);
            System.out.println("相当于await方法");
            while (true){
                SocketChannel socketChannel = ss.accept();
                if(socketChannel!=null){
                    long readSize = socketChannel.read(byteBuffer);
                    System.out.println(byteBuffer);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
