package com.xq.test.zkTest;

import org.apache.zookeeper.*;

import java.io.IOException;

/**
 * ZooKeeper做的客户端的watcher是一次性的监听：
 * 需要使用zooKeeper.exists("/xqt1",true);开启监听，而且只要做一次数据的操作，这个监听就会失效。
 * 是官方提供的，比较底层、使用起来写代码麻烦，很多功能需要自己来实现、不够直接。
 * 不好使用，一般不用。
 */
public class TestZookeeper {
    public static void main(String[] args) throws IOException, KeeperException, InterruptedException {
        ZooKeeper zooKeeper=new ZooKeeper("localhost:2181", 10000, new Watcher() {
            public void process(WatchedEvent event) {
                if(event.getType().equals(Event.EventType.NodeCreated)){
                    System.out.println("新增节点");
                } else if(event.getType().equals(Event.EventType.NodeDeleted)){
                    System.out.println("删除节点");
                }else if(event.getType() == Event.EventType.NodeDataChanged){
                    System.out.println("变更节点");
                }
//                else if(event.getState()==Event.KeeperState.SyncConnected){
//                    System.out.println(event.getType().name()+"获得了连接");
//                }
            }
        });
        ZooKeeper.States state = zooKeeper.getState();
        //开启节点监听
        zooKeeper.exists("/xqt1",true);
        zooKeeper.create("/xqt1","1".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
//        zooKeeper.exists("/xqt1",true);
        zooKeeper.setData("/xqt1","b".getBytes(),-1);
        zooKeeper.delete("/xqt1",-1);
        System.out.println(state);
    }
}
