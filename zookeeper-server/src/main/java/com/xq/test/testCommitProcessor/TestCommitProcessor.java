package com.xq.test.testCommitProcessor;

import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class TestCommitProcessor {
    /**
     * 假设有main线程为leader线程。
     * 再开两个线程用于投票
     */
    public static void main(String[] args) {
        TestCommitProcessor testCommitProcessor=new TestCommitProcessor();
        LinkedBlockingQueue<String> queue=new LinkedBlockingQueue<String>();
        Thread[] currents=new CurrentThread[2];
        currents[0]=new CurrentThread(queue);
        currents[1]=new CurrentThread(queue);
        currents[0].start();
        currents[1].start();
        testCommitProcessor.processAck(queue);
        NextThread t=new NextThread();
        System.out.println("开始执行NextThread方法");
        t.start();
        try {
            TimeUnit.MINUTES.sleep(2);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void processAck(LinkedBlockingQueue<String> queue) {
        synchronized (this) {
            Random r = new Random();
            while(true){
                if(r.nextBoolean()){
                    try {
                        TimeUnit.SECONDS.sleep(r.nextInt(10));//模拟执行用时
                        System.out.println("线程"+Thread.currentThread().getName()+"已同意");
                        queue.add(Thread.currentThread().getName());
                        break;
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
            while (true) {
                if(queue.size() >= 2){
                    notifyAll();
                    break;
                }
            }
        }
    }
}
