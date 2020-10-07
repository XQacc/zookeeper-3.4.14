package com.xq.test.testCommitProcessor;

import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class CurrentThread extends Thread {
    private LinkedBlockingQueue queue;
    public CurrentThread(LinkedBlockingQueue queue) {
        this.queue=queue;
    }
    @Override
    public void run() {
        synchronized (this){
            Random r=new Random();
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
            if(queue.size()<2){
                try {
                    System.out.println("沉睡的线程为："+Thread.currentThread().getName());
                    wait();
                    System.out.println("线程："+Thread.currentThread().getName()+"被唤醒");
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
