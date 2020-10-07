package com.xq.test.zkTest;

import org.I0Itec.zkclient.DataUpdater;
import org.I0Itec.zkclient.IZkDataListener;
import org.I0Itec.zkclient.ZkClient;

/**
 * ZkClient作为另一个开源的ZooKeeper客户端是比Zookeeper要更好使用的。
 * ZkClient是可以监听多次的
 */
public class TestzkClient {
    public static void main(String[] args) {
                ZkClient zkClient=new ZkClient("localhost:2181",10000);
        zkClient.subscribeDataChanges("/xqt2", new IZkDataListener() {
            public void handleDataChange(java.lang.String s, Object o) throws Exception {
                System.out.println(s+"改变为"+o);
            }
            public void handleDataDeleted(java.lang.String s) throws Exception {

            }
        });
        zkClient.createEphemeral("/xqt2");
        zkClient.updateDataSerialized("/xqt2", new DataUpdater<Object>() {
            public Object update(Object o) {
                return "1";
            }
        });
        zkClient.updateDataSerialized("/xqt2", new DataUpdater<Object>() {
            public Object update(Object o) {
                return "2";
            }
        });
        System.out.println("读取的值为："+zkClient.readData("/xqt2"));
        zkClient.delete("/xqt2");
    }
}
