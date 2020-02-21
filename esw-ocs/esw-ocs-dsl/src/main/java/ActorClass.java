import akka.actor.typed.*;
import akka.actor.typed.javadsl.Behaviors;
import esw.ocs.dsl.script.StrandEc;
import reactor.blockhound.BlockHound;
import akka.dispatch.MonitorableThreadFactory.*;

public class ActorClass {

    static final Behavior<String> behavior = Behaviors.receive((ctx, who) -> {
        if ("Blocking call".equals(who)) {
           Thread.sleep(2000);
        }

        System.out.println("Hello, " + who);

        return Behaviors.same();
    });

    public static void main(String[] args) throws Exception {
        BlockHound.install(builder -> {
            builder.nonBlockingThreadPredicate(p -> {
                return p.or(StrandEc.class::isInstance);
            });
            builder.disallowBlockingCallsInside(
                    "akka.dispatch.MonitorableThreadFactory.AkkaForkJoinWorkerThread",
                    "execute"
            );
            builder.allowBlockingCallsInside("java.util.concurrent.ForkJoinWorkerThread", "run");
            builder.blockingMethodCallback(m -> {
                new Exception(m.toString()).printStackTrace();
            });
        });
        System.out.println("starting");
        var greeter = ActorSystem.create(behavior, "greeter");

        greeter.tell("Akka");
        greeter.tell("Java");
        greeter.tell("Blocking call");

        Thread.currentThread().join();
    }
}
