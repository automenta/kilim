/* Copyright (c) 2006, Sriram Srinivasan
 *
 * You may distribute this software under the terms of the license 
 * specified in the file "License"
 */
package kilim;

import kilim.nio.NioSelectorScheduler.RegistrationTask;
import kilim.timerservice.Timer;
import kilim.timerservice.TimerService;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This is a basic FIFO Executor. It maintains a list of runnable tasks and hands them out to WorkerThreads. Note
 * that we don't maintain a list of all tasks, but we will at some point when we introduce monitoring/watchdog
 * services. Paused tasks are not GC'd because their PauseReasons ought to be registered with some other live
 * object.
 *
 */
public class Scheduler {
    private static final int defaultQueueSize_ =
            64 * 1024;
            //Integer.MAX_VALUE; //<- LinkedBlockingQueue

    public static volatile Scheduler defaultScheduler = null;
    public static int defaultNumberThreads;
    private static final ThreadLocal<Task> taskMgr_ = new ThreadLocal<>();

    private int numThreads;
    private AffineThreadPool affinePool_;
    protected AtomicBoolean shutdown = new AtomicBoolean(false);

    // Added for new Timer service
    private TimerService timerService;

    static {
        String s = System.getProperty("kilim.Scheduler.numThreads");
        if (s!=null)
            try {
                defaultNumberThreads = Integer.parseInt(s);
            } catch (Exception e) {
            }
        if (defaultNumberThreads==0)
            defaultNumberThreads = Math.max(1, Runtime.getRuntime().availableProcessors()-1 /* one spare */);
    }

    protected static Task getCurrentTask() {
        return taskMgr_.get();
    }

    protected static void setCurrentTask(Task t) {
        taskMgr_.set(t);
    }

    protected Scheduler() {
    }

    public Scheduler(int numThreads) {
        this(numThreads,defaultQueueSize_);
    }

    public Scheduler(int numThreads,int queueSize) {
        timerService = new TimerService();
        affinePool_ = new AffineThreadPool(numThreads,queueSize,timerService);
        this.numThreads = numThreads;
    }

    public boolean isEmptyish() {
        return affinePool_.isEmptyish();
    }

    public int numThreads() { return numThreads; }
        
    /**
     * Schedule a task to run. It is the task's job to ensure that it is not scheduled when it is runnable.
     */
    public void schedule(Task t) {
        if (t instanceof RegistrationTask)
            ((RegistrationTask) t).wake();
        else
            affinePool_.publish(t);
    }

    public void schedule(int index,Task t) {
        if (t instanceof RegistrationTask)
            assert (false);
        else
            affinePool_.publish(index,t);
    }

    public void scheduleTimer(Timer t) {
        timerService.submit(t);
    }

    /**
     * block the thread till a moment at which all scheduled tasks have completed and then shutdown the scheduler
     * does not prevent scheduling new tasks (from other threads) until the shutdown is complete so such a task
     * could be partially executed
     */
    public void idledown() {
        if (affinePool_!=null&&affinePool_.waitIdle(timerService,100))
            shutdown();
    }

    public void shutdown() {
        shutdown.set(true);
        if (defaultScheduler==this)
            defaultScheduler = null;
        if (affinePool_!=null) affinePool_.shutdown();
        timerService.shutdown();
    }

    public boolean isShutdown() {
        return shutdown.get();
    }


    public synchronized static Scheduler getDefaultScheduler() {
        if (defaultScheduler==null)
            defaultScheduler = new Scheduler(defaultNumberThreads);
        return defaultScheduler;
    }

    public static void setDefaultScheduler(Scheduler s) {
        defaultScheduler = s;
    }

}



