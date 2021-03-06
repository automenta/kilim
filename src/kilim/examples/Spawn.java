// copyright 2016 nqzero - offered under the terms of the MIT License

package kilim.examples;

import kilim.Mailbox;
import kilim.Task;

/**
 * Spawn example with one consumer, ten producers
 */
public class  Spawn {
    public static void main(String[] args) throws Exception {
        // mb is captured by all lambdas.
        Mailbox<String> mb = new Mailbox<>();
        
        //Consumer
        Task.spawn( () -> {
            while (true) {
                String s = mb.get();  // mb captured from environment.
                System.out.println(s);
            }
        });
        // Producers
        for (int i = 0; i < 10; i++) {
            final int fi = i; // Need a 'final' i to pass to closure
            Task.spawnCall( () -> {
                mb.put("Hello from " + fi);  // mb and fi captured from environment
            });
        }
    }
}
