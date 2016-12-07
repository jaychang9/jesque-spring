package net.lariverosc.jesquespring;

import java.util.Arrays;
import junit.framework.Assert;
import net.greghaines.jesque.Job;
import net.greghaines.jesque.client.Client;
import net.greghaines.jesque.meta.dao.FailureDAO;
import net.greghaines.jesque.meta.dao.KeysDAO;
import net.greghaines.jesque.meta.dao.QueueInfoDAO;
import net.greghaines.jesque.meta.dao.WorkerInfoDAO;
import net.greghaines.jesque.worker.Worker;
import net.lariverosc.jesquespring.job.MockJob;
import net.lariverosc.jesquespring.job.MockJobArgs;
import net.lariverosc.jesquespring.job.MockJobFail;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 *
 * @author Alejandro <lariverosc@gmail.com>
 */
public class JesqueSpringTest {

    private Client jesqueClient;

    private Worker worker;

    private FailureDAO failureDAO;

    private KeysDAO keysDAO;

    private QueueInfoDAO queueInfoDAO;

    private WorkerInfoDAO workerInfoDAO;

    private JedisPool jedisPool;

    @BeforeClass
    public void setUp() {
        ApplicationContext applicationContext = new ClassPathXmlApplicationContext("classpath:test-context.xml");
        jesqueClient = (Client) applicationContext.getBean("jesqueClient");
        worker = (Worker) applicationContext.getBean("worker");
        failureDAO = (FailureDAO) applicationContext.getBean("failureDAO");
        keysDAO = (KeysDAO) applicationContext.getBean("keysDAO");
        queueInfoDAO = (QueueInfoDAO) applicationContext.getBean("queueInfoDAO");
        workerInfoDAO = (WorkerInfoDAO) applicationContext.getBean("workerInfoDAO");
        jedisPool = (JedisPool) applicationContext.getBean("jedisPool");
    }

    @BeforeMethod
    public void cleanUpRedis() {
        worker.togglePause(false);
        Jedis jedis = jedisPool.getResource();
        jedis.flushDB();
        jedisPool.returnResource(jedis);
    }

    @Test
    public void shouldAddJob() {
        worker.togglePause(true);
        Assert.assertEquals(0, queueInfoDAO.getPendingCount());
        MockJob.JOB_COUNT = 0;
        Job job = new Job(MockJob.class.getName(), new Object[]{});
        for (int i = 1; i <= 5; i++) {
            jesqueClient.enqueue("JESQUE_QUEUE", job);
            Assert.assertEquals(i, queueInfoDAO.getPendingCount());
        }
        worker.togglePause(false);
        waitJob(5000);
    }

    @Test
    public void shouldProcessJobsByClass() {
        worker.togglePause(true);
        Assert.assertEquals(0, queueInfoDAO.getPendingCount());
        MockJob.JOB_COUNT = 0;
        Job job = new Job(MockJob.class.getName(), new Object[]{});
        for (int i = 1; i <= 5; i++) {
            jesqueClient.enqueue("JESQUE_QUEUE", job);
        }
        worker.togglePause(false);
        waitJob(5000);
        Assert.assertEquals(5, MockJob.JOB_COUNT);
    }

    @Test
    public void shouldProcessJobsByBeanId() {
        worker.togglePause(true);
        Assert.assertEquals(0, queueInfoDAO.getPendingCount());
        MockJob.JOB_COUNT = 0;
        Job job = new Job("mockJob", new Object[]{});
        for (int i = 1; i <= 5; i++) {
            jesqueClient.enqueue("JESQUE_QUEUE", job);
        }
        worker.togglePause(false);
        waitJob(5000);
        Assert.assertEquals(5, MockJob.JOB_COUNT);
    }

    @Test
    public void shouldAddJobWithArguments() {
        worker.togglePause(true);
        Object[] args = new Object[]{1, 2.3, true, "test", Arrays.asList("inner", 4.5)};
        Job job = new Job(MockJobArgs.class.getName(), args);
        jesqueClient.enqueue("JESQUE_QUEUE", job);
        worker.togglePause(false);
    }

    @Test
    public void shouldAddJobWithEmptyArguments() {
        worker.togglePause(true);
        Job job = new Job(MockJob.class.getName(), new Object[]{});
        jesqueClient.enqueue("JESQUE_QUEUE", job);
        worker.togglePause(false);

    }

    @Test
    public void shouldRegisterFailJob() {
        Job job = new Job(MockJobFail.class.getName(), new Object[]{});
        jesqueClient.enqueue("JESQUE_QUEUE", job);
        waitJob(3000);
        Assert.assertEquals(1, failureDAO.getCount());
    }

    public void waitJob(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException ex) {
        }
    }
}
