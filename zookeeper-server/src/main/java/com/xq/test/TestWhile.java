package com.xq.test;

import java.util.concurrent.TimeUnit;

public class TestWhile {
    public static void main(String[] args) {
        int i=0;
        boolean isEnd=false;
        while (true){
            if(isEnd){
                break;
            }
            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            switch (i++){
                case 2:
                    System.out.println("终止的数为："+i);
//                    isEnd=true;
                    break;
                default:
                    System.out.println("循环的数为"+i);
            }
        }
    }
}
