/* Copyright (c) 2006, Sriram Srinivasan
 *
 * You may distribute this software under the terms of the license 
 * specified in the file "License"
 */

package kilim;

import kilim.concurrent.SPSCQueue;
import kilim.concurrent.VolatileReferenceCell;

/**
 * This is a typed buffer that supports single producers and a single consumer.
 * It is the basic construct used for tasks to interact and synchronize with
 * each other (as opposed to direct java calls or static member variables).
 * put() and get() are the two essential functions.
 * 
 * We use the term "block" to mean thread block, and "pause" to mean fiber
 * pausing. The suffix "nb" on some methods (such as getnb()) stands for
 * non-blocking. Both put() and get() have blocking and non-blocking variants in
 * the form of putb(), putnb
 */

public class MailboxSPSC<T> extends SPSCQueue<T> implements PauseReason,
		EventPublisher {
	// TODO. Give mbox a config name and id and make monitorable

	final VolatileReferenceCell<EventSubscriber> sink = new VolatileReferenceCell<>(
			null);
	final VolatileReferenceCell<EventSubscriber> srcs = new VolatileReferenceCell<>(
			null);

	// FIX: I don't like this event design. The only good thing is that
	// we don't create new event objects every time we signal a client
	// (subscriber) that's blocked on this mailbox.
	public static final int SPACE_AVAILABLE = 1;
	public static final int MSG_AVAILABLE = 2;
	public static final int TIMED_OUT = 3;

	public static final Event spaceAvailble = new Event(MSG_AVAILABLE);
	public static final Event messageAvailable = new Event(SPACE_AVAILABLE);
	public static final Event timedOut = new Event(TIMED_OUT);

	// DEBUG steuuff
	// To do: move into monitorable stat object
	/*
	 * public int nPut = 0; public int nGet = 0; public int nWastedPuts = 0;
	 * public int nWastedGets = 0;
	 */
	public MailboxSPSC() {
		this(10);
	}

	@SuppressWarnings("unchecked")
	public MailboxSPSC(int initialSize) {
		super(initialSize);
	}

	/**
	 * Non-blocking, nonpausing fill.
	 * 
	 * @param eo
	 *            . If non-null, registers this observer and calls it with a
	 *            MessageAvailable event when a put() is done.
	 * @return buffered true if there's one, or up to burst size messages else
	 *         false
	 */
	public boolean fill(EventSubscriber eo, T[] msg) {
		boolean b = fillnb(msg);
		if (!b) {
			addMsgAvailableListener(eo);
			return false;
		}
		EventSubscriber producer = srcs.getAndSet(null);
		if (producer != null) {
			producer.onEvent(this, spaceAvailble);
		}

		return true;
	}

	/**
	 * Pausable fill Pause the caller until at least one message is available.
	 *
	 * @throws Pausable
	 */
	public void fill(T[] msg) throws Pausable {
		Task t = Task.getCurrentTask();
		boolean b = fill(t, msg);
		while (!b) {
			Task.pause(this);
			removeMsgAvailableListener(t);
			b = fill(t, msg);
		}
	}

	/**
	 * Non-blocking, nonpausing get.
	 * 
	 * @param eo
	 *            . If non-null, registers this observer and calls it with a
	 *            MessageAvailable event when a put() is done.
	 * @return buffered message if there's one, or null
	 */
	public T get(EventSubscriber eo) {
		T e = poll();
		if (e == null) {
			if (eo != null) {
				sink.set(eo);
			}
		}

		EventSubscriber producer = srcs.getAndSet(null);
		if (producer != null) {
			producer.onEvent(this, spaceAvailble);
		}
		return e;
	}

	/**
	 * put a non-null messages from buffer in the mailbox, and pause the calling
	 * task until all the messages put in the mailbox
	 */
	public void put(T[] buf) throws Pausable {
		long currentTail = tail.get();
		int n = buf.length;
		for (int i = 0; i < n; i++) {
			if (buf[i] == null) {
				throw new NullPointerException("Null is not a valid element");
			}
		}
		int count = 0;
		Task t = Task.getCurrentTask();
		boolean available = false;
		EventSubscriber subscriber;
		int m = buffer.length;
		while (n != count) {
			long wrapPoint = currentTail - m;
			while (headCache.value <= wrapPoint) {
				headCache.value = head.get();
				if (headCache.value <= wrapPoint) {
					if (available) {
						// we have put atleast one new message so we should wake
						// up if someone is waiting for message
						tail.lazySet(currentTail);
						subscriber = sink.getAndSet(null);
						if (subscriber != null) {
							removeMsgAvailableListener(subscriber);
							subscriber.onEvent(this, messageAvailable);
						}
					}
					srcs.set(t);
					Task.pause(this);
					removeSpaceAvailableListener(t);
					available = false;
				}
			}
			buffer[(int) (currentTail++) & mask] = buf[count++];
			available = true;

		}
		tail.lazySet(currentTail);
		// wake up if anybody is waiting for message
		subscriber = sink.getAndSet(null);
		if (subscriber != null) {
			// sink.value = null;
			subscriber.onEvent(this, messageAvailable);
		}
	}

	public boolean put(T msg, EventSubscriber eo) {
		if (msg == null) {
			throw new NullPointerException("Null is not a valid element");
		}
		boolean b = offer(msg);
		if (!b) {
			if (eo != null) {
				srcs.set(eo);
			}
		}
		EventSubscriber subscriber = sink.getAndSet(null);
		if (subscriber != null) {
			subscriber.onEvent(this, messageAvailable);
		}

		return b;
	}

	/**
	 * Get, don't pause or block.
	 * 
	 * @return stored message, or null if no message found.
	 */
	public T getnb() {
		return get(null);
	}

	/**
	 * @return non-null message.
	 * @throws Pausable
	 */
	public T get() throws Pausable {
		Task t = Task.getCurrentTask();
		T msg = get(t);
		while (msg == null) {
			Task.pause(this);
			removeMsgAvailableListener(t);
			msg = get(t);
		}
		return msg;
	}

	/**
	 * @return non-null message, or null if timed out.
	 * @throws Pausable
	 */
	public T get(long timeoutMillis) throws Pausable {
		final Task t = Task.getCurrentTask();
		T msg = get(t);
		long begin = System.currentTimeMillis();
		long time = timeoutMillis;
		while (msg == null) {
			t.timer_new.setTimer(time);
			t.scheduler.scheduleTimer(t.timer_new);
			Task.pause(this);
			t.timer_new.cancel();
			removeMsgAvailableListener(t);
			time = timeoutMillis - (System.currentTimeMillis() - begin);
			if (time <= 0) {
				break;
			}
			msg = get(t);
		}
		return msg;
	}

	public boolean putnb(T msg) {
		return put(msg, null);
	}

	/**
	 * Block caller until at least one message is available.
	 * 
	 * @throws Pausable
	 */
	// Not tested
	// public void untilHasMessage() throws Pausable {
	// while (hasMessage(Task.getCurrentTask()) == false) {
	// Task.pause(this);
	// }
	// }
	//
	// /**
	// * Block caller until <code>num</code> messages are available.
	// *
	// * @param num
	// * @throws Pausable
	// */
	// public void untilHasMessages(int num) throws Pausable {
	// while (hasMessages(num, Task.getCurrentTask()) == false) {
	// Task.pause(this);
	// }
	// }

	/**
	 * Block caller (with timeout) until a message is available.
	 * 
	 * @return non-null message.
	 * @throws Pausable
	 */
	// public boolean untilHasMessage(long timeoutMillis) throws Pausable {
	// final Task t = Task.getCurrentTask();
	// boolean has_msg = hasMessage(t);
	// long end = System.currentTimeMillis() + timeoutMillis;
	// while (has_msg == false) {
	// TimerTask tt = new TimerTask() {
	// public void run() {
	// MailboxSPSC.this.removeMsgAvailableListener(t);
	// t.onEvent(MailboxSPSC.this, timedOut);
	// }
	// };
	// Task.timer.schedule(tt, timeoutMillis);
	// Task.pause(this);
	// tt.cancel();
	// has_msg = hasMessage(t);
	// timeoutMillis = end - System.currentTimeMillis();
	// if (timeoutMillis <= 0) {
	// removeMsgAvailableListener(t);
	// break;
	// }
	// }
	// return has_msg;
	// }

	/**
	 * Block caller (with timeout) until <code>num</code> messages are
	 * available.
	 * 
	 * @param num
	 * @param timeoutMillis
	 * @return Message or <code>null</code> on timeout
	 * @throws Pausable
	 */
	// public boolean untilHasMessages(int num, long timeoutMillis)
	// throws Pausable {
	// final Task t = Task.getCurrentTask();
	// final long end = System.currentTimeMillis() + timeoutMillis;
	//
	// boolean has_msg = hasMessages(num, t);
	// while (has_msg == false) {
	// TimerTask tt = new TimerTask() {
	// public void run() {
	// MailboxSPSC.this.removeMsgAvailableListener(t);
	// t.onEvent(MailboxSPSC.this, timedOut);
	// }
	// };
	// Task.timer.schedule(tt, timeoutMillis);
	// Task.pause(this);
	// if (!tt.cancel()) {
	// removeMsgAvailableListener(t);
	// }
	//
	// has_msg = hasMessages(num, t);
	// timeoutMillis = end - System.currentTimeMillis();
	// if (!has_msg && timeoutMillis <= 0) {
	// removeMsgAvailableListener(t);
	// break;
	// }
	// }
	// return has_msg;
	// }

	// public boolean hasMessage(Task eo) {
	// boolean has_msg;
	// synchronized (this) {
	// int n = (int) (tail.get() - head.get());
	// if (n > 0) {
	// has_msg = true;
	// } else {
	// has_msg = false;
	// addMsgAvailableListener(eo);
	// }
	// }
	// return has_msg;
	// }
	//
	// public boolean hasMessages(int num, Task eo) {
	// boolean has_msg;
	// synchronized (this) {
	// int n = (int) (tail.get() - head.get());
	// if (n >= num) {
	// has_msg = true;
	// } else {
	// has_msg = false;
	// addMsgAvailableListener(eo);
	// }
	// }
	// return has_msg;
	// }

	/**
	 * Takes an array of mailboxes and returns the index of the first mailbox
	 * that has a message. It is possible that because of race conditions, an
	 * earlier mailbox in the list may also have received a message.
	 */
	// TODO: need timeout variant
	// public static int select(MailboxSPSC... mboxes) throws Pausable {
	// while (true) {
	// for (int i = 0; i < mboxes.length; i++) {
	// if (mboxes[i].hasMessage()) {
	// return i;
	// }
	// }
	// Task t = Task.getCurrentTask();
	// EmptySet_MsgAvListenerSpSc pauseReason = new EmptySet_MsgAvListenerSpSc(
	// t, mboxes);
	// for (int i = 0; i < mboxes.length; i++) {
	// mboxes[i].addMsgAvailableListener(pauseReason);
	// }
	// Task.pause(pauseReason);
	// for (int i = 0; i < mboxes.length; i++) {
	// mboxes[i].removeMsgAvailableListener(pauseReason);
	// }
	// }
	// }

	public void addSpaceAvailableListener(EventSubscriber spcSub) {
		srcs.set(spcSub);
	}

	public void removeSpaceAvailableListener(EventSubscriber spcSub) {

		srcs.compareAndSet(spcSub, null);
	}

	public void addMsgAvailableListener(EventSubscriber msgSub) {
		// EventSubscriber sink1 = sink.get();
		// if (sink1 != null && sink1 != msgSub) {
		// throw new AssertionError(
		// "Error: A mailbox can not be shared by two consumers.  New = "
		// + msgSub + ", Old = " + sink1);
		// }
		sink.set(msgSub);
	}

	public void removeMsgAvailableListener(EventSubscriber msgSub) {

		sink.compareAndSet(msgSub, null);

	}

	/**
	 * Attempt to put a message, and return true if successful. The thread is
	 * not blocked, nor is the task paused under any circumstance.
	 */

	/**
	 * put a non-null message in the mailbox, and pause the calling task until
	 * the mailbox has space
	 */

	public void put(T msg) throws Pausable {
		Task t = Task.getCurrentTask();
		while (!put(msg, t)) {
			Task.pause(this);
			removeSpaceAvailableListener(t);
		}
	}

	/**
	 * put a non-null message in the mailbox, and pause the calling task for
	 * timeoutMillis if the mailbox is full.
	 */

	public boolean put(T msg, int timeoutMillis) throws Pausable {
		final Task t = Task.getCurrentTask();
		long begin = System.currentTimeMillis();
		long time = timeoutMillis;
		while (!put(msg, t)) {
			t.timer_new.setTimer(time);
			t.scheduler.scheduleTimer(t.timer_new);
			Task.pause(this);
			t.timer_new.cancel();
			removeSpaceAvailableListener(t);
			time = timeoutMillis - (System.currentTimeMillis() - begin);
			if (time <= 0) {
				return false;
			}
		}
		return true;
	}

	public class BlockingSubscriber implements EventSubscriber {
		public volatile boolean eventRcvd = false;

		public void onEvent(EventPublisher ep, Event e) {
			synchronized (MailboxSPSC.this) {
				eventRcvd = true;
				MailboxSPSC.this.notify();
			}
		}

		public void blockingWait(final long timeoutMillis) {
			long start = System.currentTimeMillis();
			synchronized (MailboxSPSC.this) {
				boolean infiniteWait = timeoutMillis == 0;
				long remaining = timeoutMillis;
				while (!eventRcvd && (infiniteWait || remaining > 0)) {
					try {
						MailboxSPSC.this.wait(infiniteWait ? 0 : remaining);
					} catch (InterruptedException ie) {
					}
					long elapsed = System.currentTimeMillis() - start;
					remaining -= elapsed;
				}
			}
		}
	}

	/**
	 * retrieve a message, blocking the thread indefinitely. Note, this is a
	 * heavyweight block, unlike #get() that pauses the Fiber but doesn't block
	 * the thread.
	 * 
	 * @throws InterruptedException
	 */

	/**
	 * retrieve a msg, and block the Java thread for the time given.
	 * 
	 * @param millis
	 *            . max wait time
	 * @return null if timed out.
	 * @throws InterruptedException
	 */

	public synchronized String toString() {
		return "id:" + System.identityHashCode(this) + ' ' +
		// DEBUG "nGet:" + nGet + " " +
		// "nPut:" + nPut + " " +
		// "numWastedPuts:" + nWastedPuts + " " +
		// "nWastedGets:" + nWastedGets + " " +
				"numMsgs:" + (tail.get() - head.get());
	}

	public void clear() {
		Object value;
		do {
			value = getnb();
		} while (null != value);
	}

	// Implementation of PauseReason
	public boolean isValid(Task t) {
		if (t == sink.get()) {
			return !hasMessage();
		} else if (srcs.get() == t) {
			return !hasSpace();
		} else {
			return false;
		}
	}

}

class EmptySet_MsgAvListenerSpSc implements PauseReason, EventSubscriber {
	final Task task;
	final MailboxSPSC<?>[] mbxs;

	EmptySet_MsgAvListenerSpSc(Task t, MailboxSPSC<?>[] mbs) {
		task = t;
		mbxs = mbs;
	}

	public boolean isValid(Task t) {
		// The pauseReason is true (there is valid reason to continue
		// pausing) if none of the mboxes have any elements
		for (MailboxSPSC<?> mb : mbxs) {
			if (mb.hasMessage())
				return false;
		}
		return true;
	}

	public void onEvent(EventPublisher ep, Event e) {
		for (MailboxSPSC<?> m : mbxs) {
			if (m != ep) {
				((MailboxSPSC<?>) ep).removeMsgAvailableListener(this);
			}
		}
		task.resume();
	}

	public void cancel() {
		for (MailboxSPSC<?> mb : mbxs) {
			mb.removeMsgAvailableListener(this);
		}
	}
}
