zookeeper启动在org.apache.zookeeper.server.quorum.QuorumPeerMain中的main方法。


要调试必须在启动项中添加配置文件的全路径。


ant eclipse进行编译。不了解的可以百度。


如果遇到org.apache.zookeeper.Version这个文件报错：

是因为要实现的info没有这个文件。通过org.apache.zookeeper.version.util.VerGen这个文件。添加3个启动参数生成info这个接口目录。生成的接口文件在org/apache/zookeeper/version/Info.java中，将其复制到org.apache.zookeeper.version.Info即可解决。



For the latest information about ZooKeeper, please visit our website at:

   http://zookeeper.apache.org/

and our wiki, at:

   https://cwiki.apache.org/confluence/display/ZOOKEEPER

Full documentation for this release can also be found in docs/index.html

---------------------------
Packaging/release artifacts - Maven

    A buildable tarball is located under zookeeper/target/ directory

    The artifacts for the modules are uploaded to maven central.


Packaging/release artifacts - Ant

The release artifact contains the following jar file at the toplevel:

zookeeper-<version>.jar         - legacy jar file which contains all classes
                                  and source files. Prior to version 3.3.0 this
                                  was the only jar file available. It has the 
                                  benefit of having the source included (for
                                  debugging purposes) however is also larger as
                                  a result

The release artifact contains the following jar files in "dist-maven" directory:

zookeeper-<version>.jar         - bin (binary) jar - contains only class (*.class) files
zookeeper-<version>-sources.jar - contains only src (*.java) files
zookeeper-<version>-javadoc.jar - contains only javadoc files

These bin/src/javadoc jars were added specifically to support Maven/Ivy which have 
the ability to pull these down automatically as part of your build process. 
The content of the legacy jar and the bin+sources jar are the same.

As of version 3.3.0 bin/sources/javadoc jars contained in dist-maven directory
are deployed to the Apache Maven repository after the release has been accepted
by Apache:
  http://people.apache.org/repo/m2-ibiblio-rsync-repository/
