# BitTorrent
仿照BitTorrent实现基于P2P协议的文件共享。

## 如何运行
### 编译所有代码
```
$ make
```
执行后当前目录及各节点的子目录下的Java文件完成编译。

### 运行StartPeers启动多个节点
```
$ java StartPeers
```
首先读取当前目录中的PeerInfo.cfg配置文件，根据文件中的节点信息自动执行所有子目录下的peerProcess程序，会在当前目录下生成各节点的log日志文件。

### 清理编译生成的class文件
```
$ make clean
```

## Change Log
### v1.0
* 在本地实现各节点间的TCP连接建立
* 使用日志文件记录各节点的状态

### v2.0
* 能够实现本地的文件分享
* 日志内容完整
