package disruptor;

import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.swirlds.virtualmap.VirtualKey;
import com.swirlds.virtualmap.VirtualValue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class TransactionProcessor<K extends VirtualKey, V extends VirtualValue, T> {
    public static final int BUFFER_SIZE = 131072;   // MUST be 2^n

    Disruptor<Transaction<T>> disruptor;
    TransactionPublisher<T> publisher;

    public TransactionProcessor(
            int preFetchEventHandlers,
            Consumer<Transaction<T>> preFetchLogic,
            Consumer<Transaction<T>> txLogic
    ) {
        AtomicInteger i = new AtomicInteger();
        disruptor = new Disruptor<>(
                Transaction::new,
                BUFFER_SIZE,
                (Runnable r) -> {
                    Thread t = new Thread(r);
                    t.setName("event-handler-" + i.getAndAdd(1));
                    t.setDaemon(true);
                    return t;
                },
                ProducerType.SINGLE,
                new YieldingWaitStrategy());

        PreFetchHandler preFetchHandlers[] = new PreFetchHandler[preFetchEventHandlers];
        for (int j = 0; j < preFetchEventHandlers; j++) {
            preFetchHandlers[j] = new PreFetchHandler(j, preFetchEventHandlers, preFetchLogic);
        }

        Latch latch = new Latch();
        disruptor.handleEventsWith(preFetchHandlers)
                        .then(new TransactionHandler(latch, txLogic));
        disruptor.start();

        publisher = new TransactionPublisher<T>(disruptor.getRingBuffer(), latch);
    }

    public TransactionPublisher<T> getPublisher() {
        return publisher;
    }
}
