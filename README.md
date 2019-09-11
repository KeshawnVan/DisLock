## 背景
### 竞争条件
多个线程依赖某个共享资源，并根据共享资源的值修改该共享资源。由于线程调度的不确定性，MVVC等原因可能会导致出现预期之外的结果。
举个例子，有一家医院要求每个科室每天晚上至少有一个医生值夜班，这一天内科同时有两个医生A和B在值夜班。
A突然有紧急的事情，决定要回家，于是A查看医院的管理系统，发现此时B还在值班，所以A放心的走了。
与此同时B也有急事，也要回家，B打开医院的管理系统，一看A还在值班，B也放心的走了。
这就是一个竞争条件，解决这个问题只需要对读和写加一个互斥锁，保证整个操作是一个原子操作，并且同时只有一个线程可以访问即可。
### 分布式一致性
构建容错系统的最好方法，是找到一些带有实用保证的通用抽象，实现一次，然后让应用依赖这些保证。这与事务处理方法相同：通过使用事务，应用可以假装没有崩溃（原子性），没有其他人同时访问数据库（隔离），存储设备是完全可靠的（持久性）。即使发生崩溃，竞态条件和磁盘故障，事务抽象隐藏了这些问题，因此应用不必担心它们。
现在我们将继续沿着同样的路线前进，寻求可以让应用忽略分布式系统部分问题的抽象概念。例如，分布式系统最重要的抽象之一就是共识（consensus）：就是让所有的节点对某件事达成一致。
## 分布式锁
### 为什么选用Redis实现分布式锁
微服务多个实例之间无法使用Java内置的锁进行互斥，所以需要使用共享的内存，为什么使用RDB实现分布式锁，有以下几个原因：
#### 1.串行化
中间件有好多，为什么要选用Redis呢？其中很大一部分原因是因为Redis的工作线程是单线程的，可以提供串行化的保障。在可串行化的隔离级别下，编写互斥锁是非常容易的。
#### 2.原子命令
只有串行化是不够的，如果命令是分散的，仍然会因为顺序的不确定性导致出现预期外的结果。Redis提供了setNX，lua Script等功能帮助大家实现原子操作
#### 3.持久化
Redis提供多种序列化方式，虽然仍然不能保证数据完全不会丢失，对于锁来说足够了
#### 4.QPS高
很难想想一个串行化的系统的QPS还能这么高，Redis通过以下几点实现高QPS：
* 基于内存
* 很少有耗时操作
* 多路复用
