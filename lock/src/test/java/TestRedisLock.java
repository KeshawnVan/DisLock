import com.star.redis.Lock;
import com.star.redis.RedisLock;

public class TestRedisLock {

    public static void main(String[] args) {
        Lock lock = new RedisLock("test");
        lock.lock();
        lock.unlock();
    }
}
