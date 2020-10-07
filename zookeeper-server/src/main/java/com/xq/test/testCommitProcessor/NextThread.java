package com.xq.test.testCommitProcessor;

import java.util.concurrent.LinkedBlockingQueue;

public class NextThread extends Thread {
    @Override
    public void run() {
        System.out.println("开始执行下面的事！！！");
    }
}
