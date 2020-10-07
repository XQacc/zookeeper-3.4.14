package com.xq.test.zkTest;

import org.apache.curator.RetryPolicy;
import org.apache.curator.RetrySleeper;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.CuratorWatcher;
import org.apache.curator.framework.recipes.cache.NodeCache;
import org.apache.curator.framework.recipes.cache.NodeCacheListener;
import org.apache.curator.retry.RetryNTimes;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;

public class TestCurator {

    //Curator事件监听参考这个：https://blog.csdn.net/sqh201030412/article/details/51446434
    public static void main(String[] args) throws Exception {
        CuratorFramework client=CuratorFrameworkFactory.newClient("localhost:2181",new RetryNTimes(3,1000));
        client.start();
        client.create().withMode(CreateMode.EPHEMERAL).forPath("/xqt3");
        //监听一次
        client.getData().usingWatcher(new CuratorWatcher() {
            public void process(WatchedEvent event) throws Exception {
                if(event.getType().equals(Watcher.Event.EventType.NodeCreated)){
                    System.out.println("新增节点");
                } else if(event.getType().equals(Watcher.Event.EventType.NodeDeleted)){
                    System.out.println("删除节点");
                }else if(event.getType() == Watcher.Event.EventType.NodeDataChanged){
                    System.out.println("变更节点");
                }
//                else if(event.getState()==Event.KeeperState.SyncConnected){
//                    System.out.println(event.getType().name()+"获得了连接");
//                }
            }
        }).forPath("/xqt3");
//        client.create().withMode(CreateMode.EPHEMERAL).forPath("/xqt3");
        client.setData().forPath("/xqt3","1".getBytes());
        client.delete().forPath("/xqt3");
        System.out.println("------------------------------------------------------------------");
        //无限监听
        if(client.checkExists().forPath("/xqt4")!=null){
            System.out.println("删除 /xqt4");
            client.delete().forPath("/xqt4");
        }
//        client.create().withMode(CreateMode.EPHEMERAL).forPath("/xqt4","0".getBytes());
        final NodeCache nodeCache=new NodeCache(client,"/xqt4",false);
        nodeCache.getListenable().addListener(new NodeCacheListener() {
            public void nodeChanged() throws Exception {
                System.out.println("路径："+nodeCache.getCurrentData().getPath()+"数据："+nodeCache.getCurrentData().getStat());
            }
        });
        nodeCache.start();
        client.create().withMode(CreateMode.EPHEMERAL).forPath("/xqt4","1".getBytes());
        client.setData().forPath("/xqt4","4".getBytes());
        client.setData().forPath("/xqt4","5".getBytes());
        //删除会报错，可能删除了节点对监听存在影响
//        client.delete().forPath("/xqt4");
    }
}
