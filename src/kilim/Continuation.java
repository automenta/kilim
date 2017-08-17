// copyright 2016 nqzero - offered under the terms of the MIT License

package kilim;




/**
 * a minimal bridge or trampoline between woven and unwoven code
 * backed by a Fiber
 * giving the programmer explicit control over the event loop
 * see Task for a more general and easier to use green thread implementation that automatically handles
 * the event loop
 * see Generator for more user friendly wrapper
 * 
 * to use override execute() and call run()
 * each time run() is called, execute runs until it yields, returns or throws an exception
 * return value of true means either execute returned or threw an exception (accessible as ex())
 * with state stored in an internal Fiber field across invocations
 *
 * to reuse a Continuation, call reset()
 * 
 * Continuation provides no scheduler - it is entirely the responsibility of the calling code to
 * call run() again once the pausing condition has been satisfied
 * typically used for state machines and Generators
 * or to port an existing event loop to kilim
 * 
 * this is a low level facility, see kilim.examples.Xorshift.X2 for an example of direct use
 */
public abstract class Continuation implements Fiber.Worker {
    private static Fiber.MethodRef runnerInfo = new Fiber.MethodRef(Continuation.class.getName(),"run");
    static final FakeTask fakeTask = new FakeTask();
    public static class FakeTask extends Task {
        protected FakeTask() { super(false); }
        Fiber.MethodRef getRunnerInfo() {
            return runnerInfo;
        }
    }

    /**
     * get the stored exception
     * @return the stored Exception if execute() has thrown one, otherwise null
     */
    public Exception ex() { return ex; }
    private Exception ex;
    private kilim.Fiber fiber = new kilim.Fiber(fakeTask);

    /**
     * perform one iteration of execute()
     * the first invocation begins as a normal method
     * subsequent invocations continue from the most recent yield
     * 
     * @return true if execute returned or threw an Exception (call ex() to retrieve)
     */
    public boolean run() throws kilim.NotPausable {
        // WARNING: the name of this method must match the name in runnerInfo
        try {
            fiber.begin();
            assert (active=true)==true;
            execute( fiber );
            assert (active=false)==false;
        }
        catch (Exception        kex) { ex = kex; }
        boolean ret = ex != null || fiber.end();
        return ret;
    }
    private boolean active = false;
    
    /**
     * the top level entrypoint for the continuation
     * override this method
     * cannot be called directly - use run() instead
     * use Fiber.yield() to yield control cooperatively and return execution to the caller of run()
     */
    public void execute() throws Exception {
        Task.errNotWoven();
    }
    /**
     * the woven variant of execute() generated by the weaver
     * overriding this method will cause the weaver to leave it unchanged
     * this is a low level facility (override execute() instead)
     * @param fiber the stack information automatically provided by run()
     */
    public void execute(kilim.Fiber fiber) {}

    /**
     * reset the Continuation and corresponding Fiber
     * cannot be called inside execute()
     * subsequent invocations of run() will enter execute() as a normal method, ie at the beginning
     */
    public void reset() {
        assert !active : "invalid call to reset() during run()";
        ex = null;
        fiber.reset();
    }


    // performance notes:
    //   i3-2105: 5x Xorshift 5000000 20 --> median: 30.27
    //   units are nanos per iter
    //
    // regressions checks:
    //   assert !active: no change, -ea: +1.21

}
