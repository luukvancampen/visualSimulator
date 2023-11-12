package com.example.visualsimulator;

import javafx.application.Platform;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class Node implements Runnable {
    // this boolean keeps track of whether the transition to state.CONTEND was done based on sender initiated
    // or receiver initiated (RRTS)
    // The army papers models this using two different CONTEND states.
    boolean senderInitiated = false;
    boolean receiverInitiated = false;
    String communicatingWith;
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
    private state current_state = state.IDLE;
    private HashSet<Packet> acknowledgesPackets = new HashSet<>();

    public Node(String id, double transmissionRange, double[] coordinate, Network network) {
        this.id = id;
        this.transmissionRange = transmissionRange;
        this.coordinate = coordinate;
        this.network = network;
    }


    void reset() {
        task.cancel();
        this.current_state = state.IDLE;
        this.dataToSend = "";
//        this.senderInitiated = false;
        this.receiverInitiated = false;
    }
    public state getCurrent_state() {
        return this.current_state;
    }

    @Override
    public void run() {
        if (Objects.equals(this.id, "A")) {
//            Packet rtsPacket = new Packet(PacketType.RTS, this.coordinate, this.id, "B", new HashSet<>(), this.local_backoff.getOrDefault("B", 0), this.remote_backoff.getOrDefault("B", 0), this.exchange_seq_number.getOrDefault("B", 0));
//            this.senderInitiated = true;
//            this.communicatingWith = "B";
//            this.macawSend(rtsPacket, this);
        }
        while (true) {
            try {
                Thread.sleep(5);
                Optional<Packet> maybePacket = this.receive();
                if (maybePacket.isPresent()) {
                    System.out.println("PACKET RECEIVED: " + maybePacket.get().data);
                    this.senderInitiated = false;
                    this.receiverInitiated = false;
                }

            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    void send(String receiver, String data) throws Exception {
        //TODO throw exception when in quiet state.
        if (this.current_state == state.QUIET) {
            throw new Exception("QUIET state");
        }
        System.out.println("Sending " + data + " to " + receiver);
        this.senderInitiated = true;
        this.dataToSend = data;
        this.communicatingWith = receiver;
        this.macawSend(new Packet(PacketType.CTS, this.coordinate, this.id, "", new HashSet<>(), this.local_backoff.getOrDefault("", 0), this.remote_backoff.getOrDefault("", 0), this.exchange_seq_number.getOrDefault("", 0)), this);
    }

    Optional<Packet> receive() {
        Optional<Packet> packet = network.receive(this);
        Optional<Packet> macawPacket = Optional.empty();
        if (packet.isPresent()) {
            macawPacket = this.macawReceive(packet.get());
        }
        return macawPacket;
    }

    // Something weird is going on. In the specification in the paper it is stated that when a node sends RRTS, it goes to WFDS state. However, upon receiving the RRTS packet,
    // the receiving node will send back an RTS packet. But, as soon as that RTS arrives, the other node is still in WFDS state.

    // TODO in the following code, add conditions so that it is determinded whether this node is the recipient of a packet or simply an "observer"
    // This method deals with handling a received packet in an appropriate way.
    Optional<Packet> macawReceive(Packet packet) {
        System.out.println(this.id + " receives " + packet.type + " to " + packet.destination);
        if (this.current_state == state.IDLE && packet.type == PacketType.RTS && Objects.equals(packet.destination, this.id)) {
            System.out.println(this.id + " IS IN " + this.current_state.toString());
            // This corresponds to step 2 of the paper
            // if idle and receive RTS, send Clear to send
            reassignBackoffs(packet);
            Packet ctsPacket = new Packet(PacketType.CTS, this.coordinate, this.id, packet.originID, new HashSet<>(), this.local_backoff.getOrDefault(packet.originID, 0), this.remote_backoff.getOrDefault(packet.originID, 0), this.exchange_seq_number.getOrDefault(packet.originID, 0));
            this.current_state = state.WFDS;
            macawSend(ctsPacket, this);
//            this.network.send(ctsPacket);
            // Go to Wait for Data Send state
        } else if (this.current_state == state.WFCTS && packet.type == PacketType.CTS && Objects.equals(packet.destination, this.id)) {
            // This corresponds to step 3
            // When in WFCTS state and receive CTS...
            task.cancel();
            reassignBackoffs(packet);

            Packet dsPacket = new Packet(PacketType.DS, this.coordinate, this.id, packet.originID, new HashSet<>(), this.local_backoff.getOrDefault(packet.originID, 0), this.remote_backoff.getOrDefault(packet.originID, 0), this.exchange_seq_number.getOrDefault(packet.originID, 0));
            this.macawSend(dsPacket, this);
            this.current_state = state.SendData;
            Packet dataPacket = new Packet(PacketType.DATA, this.coordinate, this.id, packet.originID, new HashSet<>(), this.local_backoff.getOrDefault(packet.originID, 0), this.remote_backoff.getOrDefault(packet.originID, 0), this.exchange_seq_number.getOrDefault(packet.originID, 0), dataToSend);
            this.macawSend(dataPacket, this);
            this.current_state = state.WFACK;
            setTimer(this, 500);
        } else if (this.current_state == state.WFDS && packet.type == PacketType.DS && Objects.equals(packet.destination, this.id)) {
            // Step 4
            reassignBackoffs(packet);
            this.current_state = state.WFData;
            setTimer(this, 500);
        } else if (this.current_state == state.WFData && packet.type == PacketType.DATA && Objects.equals(packet.destination, this.id)) {
            // Step 5
            task.cancel();
            setTimer(this, 500);
            Packet ackPacket = new Packet(PacketType.ACK, this.coordinate, this.id, packet.originID, new HashSet<>(), this.local_backoff.getOrDefault(packet.originID, 0), this.remote_backoff.getOrDefault(packet.originID, 0), this.exchange_seq_number.getOrDefault(packet.originID, 0));
            this.acknowledgesPackets.add(ackPacket);
            this.macawSend(ackPacket, this);
            this.current_state = state.IDLE;
            return Optional.of(packet);
        } else if (this.current_state == state.WFACK && packet.type == PacketType.ACK && Objects.equals(packet.destination, this.id)) {
            // Step 6
            reassignBackoffs(packet);
            task.cancel();
            this.current_state = state.IDLE;
        } else if (this.current_state == state.IDLE && packet.type == PacketType.RTS && this.acknowledgesPackets.contains(packet)) {
            // Step 7
            Packet ackPacket = new Packet(PacketType.ACK, this.coordinate, this.id, packet.originID, new HashSet<>(), this.local_backoff.getOrDefault(packet.originID, 0), this.remote_backoff.getOrDefault(packet.originID, 0), this.exchange_seq_number.getOrDefault(packet.originID, 0));
            this.macawSend(ackPacket, this);
        } else if (packet.type == PacketType.ACK && this.current_state == state.CONTEND) {
            // Step 8
            Packet ctsPacket = new Packet(PacketType.CTS, this.coordinate, this.id, packet.originID, new HashSet<>(), this.local_backoff.getOrDefault(packet.originID, 0), this.remote_backoff.getOrDefault(packet.originID, 0), this.exchange_seq_number.getOrDefault(packet.originID, 0));
            this.macawSend(ctsPacket, this);
            this.current_state = state.WFDS;
            setTimer(this, 500);
            //TODO This seems wrong!
        } else if (this.current_state == state.QUIET && packet.type == PacketType.RTS && Objects.equals(packet.destination, this.id)) {
            // Step 9
            // This is transmission initiated by someone else.
            this.communicatingWith = packet.originID;
            this.receiverInitiated = true;
            this.senderInitiated = false;
            this.current_state = state.WFCntend;
            setTimer(this, 500);
        } else if (this.current_state == state.QUIET && packet.type == PacketType.CTS && Objects.equals(packet.destination, this.id)) {
            // Step 10
            this.current_state = state.WFCntend;
            this.remote_backoff.put(packet.originID, packet.localBackoff);
            this.remote_backoff.put(packet.destination, packet.remoteBackoff);
            this.my_backoff = packet.localBackoff;
            setTimer(this, 500);
        } else if (this.current_state == state.WFCntend && (packet.type == PacketType.CTS || packet.type == PacketType.RTS && Objects.equals(packet.destination, this.id))) {
            // Step 11
            // TODO increase timer if necessary.
            setTimer(this, 500);
            if (packet.type != PacketType.RTS) {
                this.remote_backoff.put(packet.originID, packet.localBackoff);
                this.remote_backoff.put(packet.destination, packet.remoteBackoff);
                this.my_backoff = packet.localBackoff;
            }
        } else if (this.current_state == state.WFRTS && packet.type == PacketType.RTS) {
            // Step 12
            Packet ctsPacket = new Packet(PacketType.CTS, this.coordinate, this.id, packet.originID, new HashSet<>(), this.local_backoff.getOrDefault(packet.originID, 0), this.remote_backoff.getOrDefault(packet.originID, 0), this.exchange_seq_number.getOrDefault(packet.originID, 0));
            this.current_state = state.WFDS;
            setTimer(this, 500);
        } else if (this.current_state == state.IDLE && packet.type == PacketType.RRTS && Objects.equals(packet.destination, this.id)) {
            // Step 13
            reassignBackoffs(packet);
            Packet rtsPacket = new Packet(PacketType.RTS, this.coordinate, this.id, packet.originID, new HashSet<>(), this.my_backoff, this.remote_backoff.getOrDefault(packet.originID, 0), this.exchange_seq_number.getOrDefault(packet.originID, 0));
            this.macawSend(rtsPacket, this);
//            this.current_state = state.WFCTS;
//            setTimer(this, 200);
        } else if (packet.type == PacketType.RTS && !Objects.equals(packet.destination, this.id)) {
            // Defer rule 1
            this.current_state = state.QUIET;
            setTimer(this, 500);
            // TODO set a timer sufficient for A to hear B's CTS
        } else if (packet.type == PacketType.DS && !Objects.equals(packet.destination, this.id)) {
            // Defer rule 2
            this.current_state = state.QUIET;
            this.remote_backoff.put(packet.originID, packet.localBackoff);
            this.remote_backoff.put(packet.destination, packet.remoteBackoff);
            this.my_backoff = packet.localBackoff;
            setTimer(this, 500);
            // TODO set a timer sufficient for A to transmit data and hear B's ack
        } else if (packet.type == PacketType.CTS && !Objects.equals(packet.destination, this.id)) {
            // Defer rule 3
            this.remote_backoff.put(packet.originID, packet.localBackoff);
            this.remote_backoff.put(packet.destination, packet.remoteBackoff);
            this.current_state = state.QUIET;
            this.my_backoff = packet.localBackoff;
            setTimer(this, 500);
            // TODO set a timer suffecient for B to hear A's data.
        } else if (packet.type == PacketType.RRTS && !Objects.equals(packet.destination, this.id)) {
            // Defer rule 4
            this.remote_backoff.put(packet.originID, packet.localBackoff);
            this.remote_backoff.put(packet.destination, packet.remoteBackoff);
            this.current_state = state.QUIET;
            this.my_backoff = packet.localBackoff;
            setTimer(this, 500);
            // TODO set a timer sufficient for an RTS-CTS exchange.
        }
        return Optional.empty();
    }

    void reassignBackoffs(Packet packet) {
        if (packet.sequenceNumber > this.exchange_seq_number.getOrDefault(packet.originID, 0)) {
            this.local_backoff.put(packet.originID, packet.remoteBackoff);
            this.remote_backoff.put(packet.originID, packet.localBackoff);

            Integer previousSeq = this.exchange_seq_number.getOrDefault(packet.originID, 0);
            this.exchange_seq_number.put(packet.originID, previousSeq + 1);
            this.retry_count.put(packet.originID, 1);
        } else {
            // Packet is a retransmission
            this.local_backoff.put(packet.originID, packet.localBackoff + packet.remoteBackoff - this.remote_backoff.getOrDefault(packet.originID, 0));
        }
    }

    // When a node wants to send something, it should call this method instead of directly calling the Network
    // send method. This has to do with the MACAW implementation.
    void macawSend(Packet packet, Node node) {
        // Step 1 from paper
        if (this.current_state == state.IDLE) {
            this.current_state = state.CONTEND;
            setTimer(node, 500);
            System.out.println("Timer set....");
        } else {
            Platform.runLater(() -> network.visual.showTransmission(node, packet.type.toString()));
            network.send(packet);
        }
    }

    void setTimer(Node node, long duration) {
        if (task != null) {
            task.cancel();
        }
        task = new TimerTask() {
            @Override
            public void run() {
                System.out.println("TIMER EXPIRED");
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
                        Packet rtsPacket = new Packet(PacketType.RTS, node.coordinate, node.id, node.communicatingWith, new HashSet<>(), node.local_backoff.getOrDefault(node.communicatingWith, 0), node.remote_backoff.getOrDefault(node.communicatingWith, 0), node.exchange_seq_number.getOrDefault(node.communicatingWith, 0));
                        node.macawSend(rtsPacket, node);
                        node.current_state = state.WFCTS;
                        node.setTimer(node, 600);
                    } else if (node.receiverInitiated) {
                        Packet rrtsPacket = new Packet(PacketType.RRTS, node.coordinate, node.id, node.communicatingWith, new HashSet<>(), node.local_backoff.getOrDefault(node.communicatingWith, 0), node.remote_backoff.getOrDefault(node.communicatingWith, 0), node.exchange_seq_number.getOrDefault(node.communicatingWith, 0));
                        macawSend(rrtsPacket, node);
//                        network.send(rrtsPacket);
                        // NOTE: the two papers do not correspond here. According to the army paper, the state should be IDLE.
                        // According to the other paper, the state should be WFDS. Idle makes more sense.
                        node.current_state = state.IDLE;
                        setTimer(node, 600);
                    }
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
        return this.id + " coordinate: (" + this.coordinate[0] + ", " + this.coordinate[1] + "), range: " + this.transmissionRange;
    }
}