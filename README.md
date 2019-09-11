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
* 耗时操作少
* 多路复用

### 实现加锁
用Redis来实现分布式锁最简单的方式就是在实例里创建一个键值（对于已经存在的键值再次声明返回失败），创建出来的键值一般都是有一个超时时间的（这个是Redis自带的超时特性），所以每个锁最终都会释放。
#### 最简单的加锁实现：

```
redisConnection.set(lockKey, NX , PX, expireMsecs)
```
#### 加入获取锁的重试逻辑（在有限的等待时间内重试几次）：

```
    public boolean tryLock() {
        long waitMillis = timeoutMsecs;
        value = UUID.randomUUID().toString();
        while (waitMillis >= 0) {
            long startNanoTime = System.nanoTime();
            String lockResult = setNx();
            locked = OK.equals(lockResult);
            if (locked || waitMillis == 0) return locked;
            int sleepMillis = new Random().nextInt(100);
            sleep(sleepMillis);
            long escapedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanoTime);
            waitMillis = waitMillis - escapedMillis;
        }
        return false;
    }
```
### 实现解锁
解锁是一个看似简单的操作，但是如果考虑不周也会存在很多问题
#### 最简单的方案：直接del对应的key
这个方案有什么问题呢？
假如线程A持有了锁，设置了超时时间为1秒，由于GC等各种原因当代码执行到unlock的时候，A的锁已经超时，并且此时该锁已经被B拿到，如果此时A直接delete对应的key，那么就会导致B的锁失效。此时假如有新的线程访问同一个锁，就无法保证互斥。
#### 加锁方记录时间戳
最简单的方式是在加锁方记录一个时间，解锁时判断是否超时，这样其实还是存在一个问题，就是如果还有1ms就要超时了，此时去delete还是有可能把别人的锁删掉，根本原因是由于判断和删除不是一个原子操作。
#### 使用lua script
我们可以在Redis的键值中记录锁的所有者，只有锁的所有者才能删除这个key，并且保证判断和删除是一个原子操作。
Redis提供了执行lua script的功能，帮助我们实现这个原子操作，我们只需要在加锁时指定一个唯一值，并在解锁时使用如下lua script即可实现解锁操作。

```
if redis.call("get",KEYS[1]) == ARGV[1] then
        return redis.call("del",KEYS[1])
    else
        return 0
    end
```
### 一些细节
#### 时钟
细心的同学可能会发现在实现重试的时候使用的时间API为System.nanoTime()，它和我们常用的System.currentTimeMillis()有什么区别呢？
##### 单调钟与时钟
现代计算机至少有两种不同的时钟：时钟和单调钟。尽管它们都衡量时间，但区分这两者很重要，因为它们有不同的目的。
##### 时钟
 时钟是您直观地了解时钟的依据：它根据某个日历（也称为挂钟时间（wall-clock time））返回当前日期和时间。例如，Linux上的clock_gettime(CLOCK_REALTIME)和Java中的System.currentTimeMillis()返回自epoch（1970年1月1日 午夜 UTC，格里高利历）以来的秒数（或毫秒），根据公历日历，不包括闰秒。有些系统使用其他日期作为参考点。
时钟通常与NTP同步，这意味着来自一台机器的时间戳（理想情况下）意味着与另一台机器上的时间戳相同。但是如下节所述，时钟也具有各种各样的奇特之处。特别是，如果本地时钟在NTP服务器之前太远，则它可能会被强制重置，看上去好像跳回了先前的时间点。这些跳跃以及他们经常忽略闰秒的事实，使时钟不能用于测量经过时间。
##### 单调钟
单调钟适用于测量持续时间（时间间隔），例如超时或服务的响应时间：Linux上的clock_gettime(CLOCK_MONOTONIC)，和Java中的System.nanoTime()都是单调时钟。这个名字来源于他们保证总是前进的事实（而时钟可以及时跳回）。
你可以在某个时间点检查单调钟的值，做一些事情，且稍后再次检查它。这两个值之间的差异告诉你两次检查之间经过了多长时间。但单调钟的绝对值是毫无意义的：它可能是计算机启动以来的纳秒数，或类似的任意值。特别是比较来自两台不同计算机的单调钟的值是没有意义的，因为它们并不是一回事。
#### 简化使用
##### 在Java的互斥锁中有着这样一个强制规范：
在使用阻塞等待获取锁的方式中，必须在try代码块之外，并且在加锁方法与try代码块之间没有任何可能抛出异常的方法调用，避免加锁成功后，在finally中无法解锁。
* 说明一：如果在lock方法与try代码块之间的方法调用抛出异常，那么无法解锁，造成其它线程无法成功获取锁。
* 说明二：如果lock方法在try代码块之内，可能由于其它方法抛出异常，导致在finally代码块中，unlock对未加锁的对象解锁，它会调用AQS的tryRelease方法（取决于具体实现类），抛出IllegalMonitorStateException异常。
* 说明三：在Lock对象的lock方法实现中可能抛出unchecked异常，产生的后果与说明二相同。

###### 正例：

```
Lock lock = new XxxLock();
// ...
lock.lock();
try {
    doSomething();
    doOthers();
} finally {
    lock.unlock();
}
```
###### 反例：

```
Lock lock = new XxxLock();
// ...
try {
    // 如果此处抛出异常，则直接执行finally代码块
    doSomething();
    // 无论加锁是否成功，finally代码块都会执行
    lock.lock();
    doOthers();

} finally {
    lock.unlock();
}
```
在使用尝试机制来获取锁的方式中，进入业务代码块之前，必须先判断当前线程是否持有锁。锁的释放规则与锁的阻塞等待方式相同。
说明：Lock对象的unlock方法在执行时，它会调用AQS的tryRelease方法（取决于具体实现类），如果当前线程不持有锁，则抛出IllegalMonitorStateException异常。
同样的问题放在RedisLock这里也是存在的，比如在未获取锁的时候就去解锁，虽然RedisLock不会报错，但是也会增加一次Redis访问。尽管如此，通过一些改造，RedisLock能改进一些使用的体验。
##### 加锁
RedisLock加锁不同于Java的内置锁，会出现很多种异常，如果我们期望在外部捕获，由于lock()不能写在try finnaly块中，那么我们需要在写一层try catch，实在过于冗余。
如果能lock能写在try块里或者try finally能够简洁优雅一些就好了
使用Java 7提供的try witch resource可以使得操作更加简洁

```
try(Lock lock = new RedisLock()) {

}
```
想要RedisLock支持该特性只需要实现AutoCloseable接口
看起来是不是很好用，但是我们好像疏忽了一点
使用try witch resource会导致不管有没有或者到锁都会调用unlock方法
##### 解锁
所幸我们实现的RedisLock和Java中提供的锁有两个本质区别：
1. Java中提供的锁无法修改代码
2. Java中的锁在内存中是多线程访问的，而RedisLock是局部变量（RedisLock只是Client，真正的锁在Redis）
因此我们可以在RedisLock中增加一个变量记录是否加锁成功，如果未加锁成功调用解锁方法就直接return了

```
   public void unlock() {
        if (!locked) return;
        RedisCommands<String, String> redisCommands = RedisConnections.getConnection(6).sync();
        Object result = redisCommands.eval(UNLOCK_SCRIPT, ScriptOutputType.INTEGER, new String[]{lockKey}, value);
        if (CastUtil.castInt(result) < 0) {
        }
        locked = false;
    }
```
## RedLock
我们之前的实现都是基于单节点Redis实现的，但是基于单Redis节点的分布式锁无法解决failover的问题：
如Redis节点宕机了，那么所有客户端就都无法获得锁了，服务变得不可用。为了提高可用性，我们可以给这个Redis节点挂一个Slave，当Master节点不可用的时候，系统自动切到Slave上（failover）。但由于Redis的主从复制（replication）是异步的，这可能导致在failover过程中丧失锁的安全性。考虑下面的执行序列：
1. 客户端1从Master获取了锁。
2. Master宕机了，存储锁的key还没有来得及同步到Slave上。
3. Slave升级为Master。
4. 客户端2从新的Master获取到了对应同一个资源的锁。
于是，客户端1和客户端2同时持有了同一个资源的锁。锁的安全性被打破。
针对这个问题，Redis的作者antirez设计了Redlock算法。
Redlock的算法描述就放在Redis的官网上：https://redis.io/topics/distlock
关于这个算法的可靠性，也产生了一场争论，大家对RedLock感兴趣可以看下以下文章：
* [基于Redis的分布式锁到底安全吗（上）？](http://zhangtielei.com/posts/blog-redlock-reasoning.html)
* [基于Redis的分布式锁到底安全吗（下）？](http://zhangtielei.com/posts/blog-redlock-reasoning-part2.html)
