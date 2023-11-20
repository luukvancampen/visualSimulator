package com.example.visualsimulator;

import javafx.application.Platform;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public class Node implements Runnable {
    // this boolean keeps track of whether the transition to state.CONTEND was done
    // based on sender initiated
    // or receiver initiated (RRTS)
    // The army papers models this using two different CONTEND states.
    boolean senderInitiated = false;
    boolean receiverInitiated = false;
    String communicatingWith;
    Consumer<Boolean> callback;
    TimerTask task;

    int my_backoff;
    Map<String, Integer> local_backoff = new HashMap<>();
    Map<String, Integer> remote_backoff = new HashMap<>();
    Map<String, Integer> exchange_seq_number = new HashMap<>();
    Map<String, Integer> retry_count = new HashMap<>();
    String id;
    double transmissionRange;
    double[] coordinate;
    Network network;
    String dataToSend = "";
    NetworkLayerPacket currentlySendingNLPacket;
    private state current_state = state.IDLE;
    private final HashSet<LinkLayerPacket> acknowledgesPackets = new HashSet<>();

    private final Map<String, List<NetworkLayerPacket>> maintenanceBuffer;


    public Node(String id, double transmissionRange, double[] coordinate, Network network) {
        this.id = id;
        this.transmissionRange = transmissionRange;
        this.coordinate = coordinate;
        this.network = network;


        maintenanceBuffer = new HashMap<>();
    }

    public state getCurrent_state() {
        return this.current_state;
    }

    @Override
    public void run() {
        // TODO make sure broadcasts are not received twice and to self?
        while (true) {
            try {
                Thread.sleep(50);
                Optional<LinkLayerPacket> maybePacket = this.network.receive(this);
                if (maybePacket.isPresent()) {
                    this.receiverInitiated = false;
                    macawReceive(maybePacket.get());
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    void send(String receiver, String data) throws Exception {
        // TODO throw exception when in quiet state.
        if (this.current_state != state.IDLE) {
            throw new Exception("QUIET");
        }
        // System.out.println("Sending " + data + " to " + receiver);
        this.senderInitiated = true;
        this.dataToSend = data;
        this.communicatingWith = receiver;
        this.macawSend(new LinkLayerPacket(PacketType.CTS, this.coordinate, this.id, "", new HashSet<>(), this.local_backoff.getOrDefault("", 0), this.remote_backoff.getOrDefault("", 0), this.exchange_seq_number.getOrDefault("", 0)), this, success -> {});


//        this.macawSend(receiver, data, success -> {});
        // this.macawSend(new LinkLayerPacket(PacketType.CTS, this.coordinate, this.id,
        // "", new HashSet<>(), this.local_backoff.getOrDefault("", 0),
        // this.remote_backoff.getOrDefault("", 0),
        // this.exchange_seq_number.getOrDefault("", 0)), this, success -> {});
    }

    // TODO make sure data is printed when a packet has successfully arrived through
    // routing.
    // Optional<String> receiveData() {
    // Optional<NetworkLayerPacket> nlPacket = this.receiveDSR();
    // return nlPacket.map(networkLayerPacket -> networkLayerPacket.data);
    // }

//    Optional<LinkLayerPacket> receiveFromLinkLayer() throws InterruptedException {
//        Optional<LinkLayerPacket> packet = network.receive(this);
//        if (packet.isPresent()) {
//            System.out.println("Node " + this.id + " receives " + packet.get().type + " with address "
//                    + packet.get().macDestination);
//
//        }
//        Optional<LinkLayerPacket> macawPacket = Optional.empty();
//        if (packet.isPresent()) {
//            if (packet.get().macDestination == "ff:ff:ff:ff:ff:ff") {
//                // TODO checek this carefully
//                System.out.println("RECEIVED BROADCAST");
//
////                this.current_state = state.IDLE;
//                // Bypass the mac layer, instantly pass the packet to dsrReceive.
//                return packet;
//            } else {
//                System.out.println("ELSE Node " + this.id + " receives " + packet.get().type + " with address "
//                        + packet.get().macDestination + " IN STATE " + this.current_state);
//
//                macawPacket = this.macawReceive(packet.get());
//            }
//        }
//        return macawPacket;
//    }

    // Process a packet using DSR.
    // Returns packets with data in them if any.
    // TODO Justin please check if this makes sense.


    // Something weird is going on. In the specification in the paper it is stated
    // that when a node sends RRTS, it goes to WFDS state. However, upon receiving
    // the RRTS packet,
    // the receiving node will send back an RTS packet. But, as soon as that RTS
    // arrives, the other node is still in WFDS state.

    // TODO in the following code, add conditions so that it is determinded whether
    // this node is the recipient of a packet or simply an "observer"
    // This method deals with handling a received packet in an appropriate way.
    Optional<LinkLayerPacket> macawReceive(LinkLayerPacket packet) throws InterruptedException {
        // This if statement deals with broadcasting. It essentially makes sure that
        System.out.println("Node " + this.id + " in state " + this.current_state + " while receiving " + packet.type + " for " + packet.macDestination);

        if (packet.macDestination.equals(this.id) && packet.type == PacketType.DATA) {
            System.out.println(this.id + "receiving data in state " + this.current_state);
        }
        if (this.current_state == state.IDLE && packet.type == PacketType.RTS
                && (Objects.equals(packet.macDestination, this.id))) {
            System.out.println(this.id + " IS IN " + this.current_state);
            // This corresponds to step 2 of the paper
            // if idle and receive RTS, send Clear to send
            reassignBackoffs(packet);
            LinkLayerPacket ctsPacket = new LinkLayerPacket(PacketType.CTS, this.coordinate, this.id, packet.macSource,
                    new HashSet<>(), this.local_backoff.getOrDefault(packet.macSource, 0),
                    this.remote_backoff.getOrDefault(packet.macSource, 0),
                    this.exchange_seq_number.getOrDefault(packet.macSource, 0));
            this.current_state = state.WFDS;
            setTimer(this, ThreadLocalRandom.current().nextInt(2000, 2001));
            macawSend(ctsPacket, this, succss -> {
            });
            // this.network.send(ctsPacket);
            // Go to Wait for Data Send state
        } else if (this.current_state == state.WFCTS && packet.type == PacketType.CTS
                && (Objects.equals(packet.macDestination, this.id))) {
            // This corresponds to step 3
            // When in WFCTS state and receive CTS...
            task.cancel();
            reassignBackoffs(packet);

            LinkLayerPacket dsPacket = new LinkLayerPacket(PacketType.DS, this.coordinate, this.id, packet.macSource,
                    new HashSet<>(), this.local_backoff.getOrDefault(packet.macSource, 0),
                    this.remote_backoff.getOrDefault(packet.macSource, 0),
                    this.exchange_seq_number.getOrDefault(packet.macSource, 0));
            this.macawSend(dsPacket, this, success -> {
            });
            this.current_state = state.SendData;
            LinkLayerPacket dataPacket = new LinkLayerPacket(PacketType.DATA, this.coordinate, this.id,
                    packet.macSource, new HashSet<>(), this.local_backoff.getOrDefault(packet.macSource, 0),
                    this.remote_backoff.getOrDefault(packet.macSource, 0),
                    this.exchange_seq_number.getOrDefault(packet.macSource, 0), currentlySendingNLPacket);
            this.macawSend(dataPacket, this, success -> {
            });
            this.current_state = state.WFACK;
            setTimer(this, 1500);
        } else if (this.current_state == state.WFDS && packet.type == PacketType.DS
                && (Objects.equals(packet.macDestination, this.id))) {
            // Step 4
            reassignBackoffs(packet);
            this.current_state = state.WFData;
            System.out.println(this.id + " in wfdata");
            setTimer(this, 5500);
        } else if (this.current_state == state.WFData && packet.type == PacketType.DATA
                && (Objects.equals(packet.macDestination, this.id))) {
            // Step 5
            task.cancel();
//            setTimer(this, 1500);
            LinkLayerPacket ackPacket = new LinkLayerPacket(PacketType.ACK, this.coordinate, this.id, packet.macSource,
                    new HashSet<>(), this.local_backoff.getOrDefault(packet.macSource, 0),
                    this.remote_backoff.getOrDefault(packet.macSource, 0),
                    this.exchange_seq_number.getOrDefault(packet.macSource, 0));
            this.acknowledgesPackets.add(ackPacket);
            System.out.println("Should be sending ACK");
            this.macawSend(ackPacket, this, success -> {
            });
            Thread.sleep(20);
            this.current_state = state.IDLE;
            return Optional.of(packet);
        } else if (this.current_state == state.WFACK && packet.type == PacketType.ACK
                && (Objects.equals(packet.macDestination, this.id))) {
            // Step 6
            reassignBackoffs(packet);
            this.callback.accept(true);
            task.cancel();
            this.current_state = state.IDLE;
        } else if (this.current_state == state.IDLE && packet.type == PacketType.RTS
                && this.acknowledgesPackets.contains(packet)) {
            // Step 7
            LinkLayerPacket ackPacket = new LinkLayerPacket(PacketType.ACK, this.coordinate, this.id, packet.macSource,
                    new HashSet<>(), this.local_backoff.getOrDefault(packet.macSource, 0),
                    this.remote_backoff.getOrDefault(packet.macSource, 0),
                    this.exchange_seq_number.getOrDefault(packet.macSource, 0));
            this.macawSend(ackPacket, this, success -> {
            });
        } else if (packet.type == PacketType.ACK && this.current_state == state.CONTEND) {
            // Step 8
            LinkLayerPacket ctsPacket = new LinkLayerPacket(PacketType.CTS, this.coordinate, this.id, packet.macSource,
                    new HashSet<>(), this.local_backoff.getOrDefault(packet.macSource, 0),
                    this.remote_backoff.getOrDefault(packet.macSource, 0),
                    this.exchange_seq_number.getOrDefault(packet.macSource, 0));
            this.macawSend(ctsPacket, this, success -> {
            });
            if (packet.macSource == "") {
                System.out.println("SENDING WITH EMPTY DESTINATION 1");
            }
            this.current_state = state.WFDS;
            setTimer(this, ThreadLocalRandom.current().nextInt(500, 1000));
            // TODO This seems wrong!
        } else if (this.current_state == state.QUIET && packet.type == PacketType.RTS
                && (Objects.equals(packet.macDestination, this.id))) {
            // Step 9
            // This is transmission initiated by someone else.
            this.communicatingWith = packet.macSource;
            this.receiverInitiated = true;
            this.senderInitiated = false;
            this.current_state = state.WFCntend;
            setTimer(this, ThreadLocalRandom.current().nextInt(400, 900));
        } else if (this.current_state == state.QUIET && packet.type == PacketType.CTS
                && (Objects.equals(packet.macDestination, this.id))) {
            // Step 10
            this.current_state = state.WFCntend;
            this.remote_backoff.put(packet.macSource, packet.localBackoff);
            this.remote_backoff.put(packet.macDestination, packet.remoteBackoff);
            this.my_backoff = packet.localBackoff;
            setTimer(this, ThreadLocalRandom.current().nextInt(400, 900));
        } else if (this.current_state == state.WFCntend && (packet.type == PacketType.CTS
                || packet.type == PacketType.RTS && (Objects.equals(packet.macDestination, this.id)))) {
            // Step 11
            // TODO increase timer if necessary.
            setTimer(this, ThreadLocalRandom.current().nextInt(400, 900));
            if (packet.type != PacketType.RTS) {
                this.remote_backoff.put(packet.macSource, packet.localBackoff);
                this.remote_backoff.put(packet.macDestination, packet.remoteBackoff);
                this.my_backoff = packet.localBackoff;
            }
        } else if (this.current_state == state.WFRTS && packet.type == PacketType.RTS) {
            // Step 12
            LinkLayerPacket ctsPacket = new LinkLayerPacket(PacketType.CTS, this.coordinate, this.id, packet.macSource,
                    new HashSet<>(), this.local_backoff.getOrDefault(packet.macSource, 0),
                    this.remote_backoff.getOrDefault(packet.macSource, 0),
                    this.exchange_seq_number.getOrDefault(packet.macSource, 0));
            this.current_state = state.WFDS;
            macawSend(ctsPacket, this, success -> {
            });
            if (packet.macSource == "") {
                System.out.println("SENDING WITH EMPTY DESTINATION 2");
            }
            setTimer(this, ThreadLocalRandom.current().nextInt(400, 900));
        } else if (this.current_state == state.IDLE && packet.type == PacketType.RRTS
                && (Objects.equals(packet.macDestination, this.id))) {
            // Step 13
            reassignBackoffs(packet);
            LinkLayerPacket rtsPacket = new LinkLayerPacket(PacketType.RTS, this.coordinate, this.id, packet.macSource,
                    new HashSet<>(), this.my_backoff, this.remote_backoff.getOrDefault(packet.macSource, 0),
                    this.exchange_seq_number.getOrDefault(packet.macSource, 0));
            this.macawSend(rtsPacket, this, success -> {
            });
            // this.current_state = state.WFCTS;
            // setTimer(this, 200);
        } else if (packet.type == PacketType.RTS && !Objects.equals(packet.macDestination, this.id)) {
            // Defer rule 1
            this.current_state = state.QUIET;
            setTimer(this, ThreadLocalRandom.current().nextInt(400, 900));
            // TODO set a timer sufficient for A to hear B's CTS
        } else if (packet.type == PacketType.DS && !Objects.equals(packet.macDestination, this.id)) {
            // Defer rule 2
            this.current_state = state.QUIET;
            this.remote_backoff.put(packet.macSource, packet.localBackoff);
            this.remote_backoff.put(packet.macDestination, packet.remoteBackoff);
            this.my_backoff = packet.localBackoff;
            setTimer(this, ThreadLocalRandom.current().nextInt(400, 900));
            // TODO set a timer sufficient for A to transmit data and hear B's ack
        } else if (packet.type == PacketType.CTS && !Objects.equals(packet.macDestination, this.id)) {
            // Defer rule 3
            this.remote_backoff.put(packet.macSource, packet.localBackoff);
            this.remote_backoff.put(packet.macDestination, packet.remoteBackoff);
            this.current_state = state.QUIET;
            this.my_backoff = packet.localBackoff;
            setTimer(this, ThreadLocalRandom.current().nextInt(400, 900));
            // TODO set a timer suffecient for B to hear A's data.
        } else if (packet.type == PacketType.RRTS && !Objects.equals(packet.macDestination, this.id)) {
            // Defer rule 4
            this.remote_backoff.put(packet.macSource, packet.localBackoff);
            this.remote_backoff.put(packet.macDestination, packet.remoteBackoff);
            this.current_state = state.QUIET;
            this.my_backoff = packet.localBackoff;
            setTimer(this, ThreadLocalRandom.current().nextInt(400, 900));
            // TODO set a timer sufficient for an RTS-CTS exchange.
        }
        return Optional.empty();
    }

    void reassignBackoffs(LinkLayerPacket packet) {
        if (packet.sequenceNumber > this.exchange_seq_number.getOrDefault(packet.macSource, 0)) {
            this.local_backoff.put(packet.macSource, packet.remoteBackoff);
            this.remote_backoff.put(packet.macSource, packet.localBackoff);

            Integer previousSeq = this.exchange_seq_number.getOrDefault(packet.macSource, 0);
            this.exchange_seq_number.put(packet.macSource, previousSeq + 1);
            this.retry_count.put(packet.macSource, 1);
        } else {
            // Packet is a retransmission
            this.local_backoff.put(packet.macSource,
                    packet.localBackoff + packet.remoteBackoff - this.remote_backoff.getOrDefault(packet.macSource, 0));
        }
    }

    // When a node wants to send something, it should call this method instead of
    // directly calling the Network
    // send method. This has to do with the MACAW implementation.
    void macawSend(LinkLayerPacket packet, Node node, Consumer<Boolean> callback) {
        this.callback = callback;

        // Step 1 from paper
        if (this.current_state == state.IDLE) {
            this.current_state = state.CONTEND;
            setTimer(node, 500);
            System.out.println("Timer set....");
        } else {
            if (!packet.macDestination.equals("")) {
                Platform.runLater(() -> network.visual.showTransmission(node, packet.type.toString()));
                if (packet.type == PacketType.ACK) {
                    System.out.println("ACK SENT!");
                }
                network.send(packet);
            }
        }
    }

    void reset() {
        task.cancel();
        this.current_state = state.IDLE;
        this.dataToSend = "";
//        this.senderInitiated = false;
        this.receiverInitiated = false;
    }

    void setTimer(Node node, long duration) {
        if (task != null) {
            task.cancel();
        }
        task = new TimerTask() {
            @Override
            public void run() {
                System.out.println("Node " + node.id + " expired in state " + node.current_state);
                if (node.current_state == state.WFCntend) {
                    // first timeout rule
                    node.setTimer(node, 600);
                    node.current_state = state.CONTEND;
                } else if (node.current_state == state.CONTEND) {
                    // second timeout rule
                    System.out.println("CURRENT STATE IS CONTEND");
                    // TODO this part is why C does not go back to IDLE.
                    System.out.println("sender initiated: " + node.senderInitiated);
                    System.out.println("Receiver initiated: " + node.receiverInitiated);
                    if (node.senderInitiated) {
                        System.out.println("SENDER INITIATED");
                        LinkLayerPacket rtsPacket = new LinkLayerPacket(PacketType.RTS, node.coordinate, node.id,
                                node.communicatingWith, new HashSet<>(),
                                node.local_backoff.getOrDefault(node.communicatingWith, 0),
                                node.remote_backoff.getOrDefault(node.communicatingWith, 0),
                                node.exchange_seq_number.getOrDefault(node.communicatingWith, 0), currentlySendingNLPacket);
                        node.macawSend(rtsPacket, node, success -> {});
                        node.current_state = state.WFCTS;
                        System.out.println("GOING TO WFCTS");
                        node.setTimer(node, ThreadLocalRandom.current().nextInt(1000, 1500));
                    } else if (node.receiverInitiated) {
                        LinkLayerPacket rrtsPacket = new LinkLayerPacket(PacketType.RRTS, node.coordinate, node.id,
                                node.communicatingWith, new HashSet<>(),
                                node.local_backoff.getOrDefault(node.communicatingWith, 0),
                                node.remote_backoff.getOrDefault(node.communicatingWith, 0),
                                node.exchange_seq_number.getOrDefault(node.communicatingWith, 0));
                        macawSend(rrtsPacket, node, success -> {
                        });
                        // network.send(rrtsPacket);
                        // NOTE: the two papers do not correspond here. According to the army paper, the
                        // state should be IDLE.
                        // According to the other paper, the state should be WFDS. Idle makes more
                        // sense.
                        node.current_state = state.IDLE;
                        setTimer(node, ThreadLocalRandom.current().nextInt(400, 900));
                    }
                } else if (node.current_state == state.WFData) {
                    System.out.println(node.id + " Timer expired while in WFDATA");
                    node.current_state = state.IDLE;
                } else {
                    node.current_state = state.IDLE;
                }
            }
        };

        Timer timer = new Timer();
        // TODO Random timer, range might not make sense.
        timer.schedule(task, ThreadLocalRandom.current().nextInt((int) duration, (int) duration + 1));
    }

    @Override
    public String toString() {
        return this.id + " coordinate: (" + this.coordinate[0] + ", " + this.coordinate[1] + "), range: "
                + this.transmissionRange;
    }

}