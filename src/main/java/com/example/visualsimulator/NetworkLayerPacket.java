package com.example.visualsimulator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class NetworkLayerPacket implements Cloneable {
    // Network.
    String data;
    double[] sourceCoordinate;
    Set<Node> received;

    // MAC.
    String macSource;
    String macDestination;

    // IP.
    String ipSource;
    String ipDestination;
    int timeToLive;
    boolean isPiggyBack;
    NetworkLayerPacket piggyBack;

    // Routing
    Set<OptionType> optionTypes;
    List<String> route;
    int salvage;

    // RouteRequest
    int identification;
    String targetAddress;

    // SourceRoute
    List<String> sourceRoute;
    int segmentsLeft;

    // RouteError
    String errorSourceAddress;
    String errorDestinationAddress;
    String unreachableAddress;

    @Override
    public NetworkLayerPacket clone() {
        NetworkLayerPacket packet;

        try {
            packet = (NetworkLayerPacket) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError();
        }

        packet.received = new HashSet<>(received);

        if (optionTypes != null) {
            packet.optionTypes = new HashSet<>(optionTypes);
        }

        if (route != null) {
            packet.route = new ArrayList<>(route);
        }

        if (sourceRoute != null) {
            packet.sourceRoute = new ArrayList<>(sourceRoute);
        }

        return packet;
    }

    public void print() {
        printPacket(2);
    }

    private void printPacket(int indentation) {
        if (!isPiggyBack) {
            System.out.println(macSource + " -> " + macDestination);
        }

        printIdentation(indentation);
        System.out.println("PL: SCOORD=[" + sourceCoordinate[0] + "," + sourceCoordinate[1] + "]");

        printIdentation(indentation);
        System.out.println("IP: " + ipSource + " -> " + ipDestination + ", TTL=" + timeToLive + ", DATA=" + data);

        for (OptionType optionType : optionTypes) {
            if (optionType == OptionType.RouteRequest) {
                printIdentation(indentation);
                System.out.println(
                        "RREQ: ID=" + identification + ", TARGET=" + targetAddress + ", ROUTE=" + route);
            }

            if (optionType == OptionType.RouteReply) {
                printIdentation(indentation);
                System.out.println(
                        "RREP: ID=" + identification + ", ROUTE=" + route);
            }

            if (optionType == OptionType.RouteError) {
                printIdentation(indentation);
                System.out.println(
                        "RERR");
            }

            if (optionType == OptionType.SourceRoute) {
                printIdentation(indentation);
                System.out.println(
                        "SR: SLEF=" + segmentsLeft + ", SALVAGE=" + salvage + ", ROUTE=" + sourceRoute);
            }

        }

        if (piggyBack != null) {
            piggyBack.printPacket(indentation + 2);
        }
    }

    private void printIdentation(int identation) {
        for (int i = 0; i < identation; i++) {
            System.out.print(" ");
        }
    }
}
