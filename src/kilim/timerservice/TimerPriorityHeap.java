// Copyright 2014 nilangshah - offered under the terms of the MIT License

package kilim.timerservice;

import java.util.Arrays;

public class TimerPriorityHeap {

	public static final int QUEUE_SIZE_INIT = 32;
	public static final int QUEUE_SIZE_INC = 8;

	private Timer[] queue;
	private int size = 0;

	public TimerPriorityHeap() {
		this(QUEUE_SIZE_INIT);
	}

	public TimerPriorityHeap(int size) {
		queue = new Timer[size];
	}

	public int size() {
		return size;
	}

	public Timer peek() {
		return queue[1];
	}

	public boolean isEmpty() {
		return size == 0;
	}

	public void add(Timer task) {

		if (size + 1 == queue.length)
			queue = Arrays.copyOf(queue, grow());
		queue[++size] = task;
		heapifyUp(size);

	}

	private int grow() {
		//return 2 * queue.length;
		return queue.length + QUEUE_SIZE_INC;
	}

	void reschedule(int i) {
		heapifyUp(i);
		heapifyDown(i);
	}

	private void heapifyUp(int k) {
		while (k > 1) {
			int j = k >> 1;
			Timer[] q = this.queue;
			if (q[j].getExecutionTime() <= q[k].getExecutionTime())
				break;
			Timer tmp = q[j];
			q[j] = q[k];
			q[j].index = j;
			q[k] = tmp;
			q[k].index = k;
			k = j;
		}
	}

	private void heapifyDown(int k) {
		int j;
		Timer[] q = this.queue;
		while ((j = k << 1) <= size && j > 0) {
			if (j < size
					&& q[j].getExecutionTime() > q[j + 1]
							.getExecutionTime())
				j++;
			if (q[k].getExecutionTime() <= q[j].getExecutionTime())
				break;
			Timer tmp = q[j];
			q[j] = q[k];
			q[j].index = j;
			q[k] = tmp;
			q[k].index = k;
			k = j;
		}
	}

	public void poll() {
		Timer[] q = this.queue;
		q[1] = q[size];
		q[1].index = 1;
		q[size--] = null;
		heapifyDown(1);

	}

	// private void heapify() {
	// for (int i = size / 2; i >= 1; i--)
	// heapifyDown(i);
	// }

}
