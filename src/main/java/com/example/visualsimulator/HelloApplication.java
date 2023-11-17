package com.example.visualsimulator;

import javafx.animation.KeyFrame;
import javafx.animation.ParallelTransition;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.util.Duration;
import javafx.util.Pair;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class HelloApplication extends Application {

    HashSet<Line> routeLines = new HashSet<>();
    HashSet<Pair<Node, Node>> alreadyDrawn = new HashSet<>();
    Integer colorCounter = 0;

    boolean simulationRunning = false;
    List<Thread> simulationThreads = new LinkedList<>();
    private static final Duration ANIMATION_DURATION = Duration.millis(10);
    HashMap<String, Integer> framecountMap = new HashMap<>();
    HashMap<String, Circle> circleMap = new HashMap<>();
    HashMap<String, Circle> rangeMap = new HashMap<>();

    // double scale = 1.0;
    LinkedList<Node> nodeList = new LinkedList<>();
    VBox nodeVBox = new VBox();
    Network network = new Network();
    Canvas canvas = null;
    Pane pane = null;

    @Override
    public void start(Stage stage) {
        Thread networkThread = new Thread(network, "Network thread");
        networkThread.start();

        VBox container = new VBox();
        Scene scene = new Scene(container);
        stage.setTitle("Hello!");
        stage.setScene(scene);

        stage.setWidth(800);
        stage.setHeight(600);

        ToggleGroup toggleGroup = new ToggleGroup();
        HBox radioHBox = new HBox();
        RadioButton randomRadio = new RadioButton("Random");
        RadioButton manualRadio = new RadioButton("Manual");
        RadioButton fixedLongRouteRadio = new RadioButton("Fixed long route");

        randomRadio.setToggleGroup(toggleGroup);
        manualRadio.setToggleGroup(toggleGroup);
        fixedLongRouteRadio.setToggleGroup(toggleGroup);

        canvas = new Canvas(400, 300);

        radioHBox.getChildren().addAll(randomRadio, manualRadio, fixedLongRouteRadio);
        VBox paneBox = new VBox();

        pane = new Pane();
        pane.setMinSize(600, 600);
        paneBox.getChildren().add(pane);

        EventHandler<ActionEvent> radioHandler = actionEvent -> {
            RadioButton button = (RadioButton) actionEvent.getSource();
            String option = button.getText();
            if (option.equals("Manual")) {
                nodeList.clear();
                Button addNodeButton = new Button("Add node");
                container.getChildren().add(addNodeButton);

                addNodeButton.setOnAction(addManualNodeHandler);
                addNodeButton.setUserData(pane);

            } else if (option.equals("Random")) {
                nodeList.clear();
                HBox numberOfNodesHBox = new HBox();
                Text numberOfNodesText = new Text("Number of nodes: ");
                TextField numberOfNodesTextField = new TextField("0");
                numberOfNodesTextField.textProperty().addListener((observable, oldValue, newValue) -> {
                    if (!newValue.matches("\\d*")) {
                        numberOfNodesTextField.setText(newValue.replaceAll("\\D", ""));
                    }
                    if (!newValue.equals("")) {
                        nodeList.clear();
                        pane.getChildren().clear();
                        nodeList.clear();
                        Integer numberOfNodes = Integer.parseInt(numberOfNodesTextField.getText());
                        for (int i = 0; i < numberOfNodes; i++) {
                            double[] coordinate = {
                                    ThreadLocalRandom.current().nextInt(20, 200),
                                    ThreadLocalRandom.current().nextInt(20, 200)
                            };

                            double[] newCoordinate = {
                                    getRandomNumber(200, 600),
                                    getRandomNumber(200, 600)
                            };
                            // Node node = new Node(getNthLetter(i + 1),
                            // ThreadLocalRandom.current().nextInt(50, 100), coordinate, network);
                            Node node = new Node(getNthLetter(i + 1), newCoordinate, 200, network);
                            nodeList.add(node);
                            drawNodeNew(node, pane);
                        }
                    }
                });

                HBox simulationButtonBox = new HBox();
                Text simulationStarStopText = new Text("Random simulation: ");
                Button startRandomSimulationButton = new Button("Start");
                Button stopRandomSimulationButton = new Button("Stop");

                startRandomSimulationButton.setOnAction(startRandomSimulation);
                stopRandomSimulationButton.setOnAction(stopRandomSimulation);

                simulationButtonBox.getChildren().addAll(simulationStarStopText, startRandomSimulationButton,
                        stopRandomSimulationButton);

                paneBox.getChildren().add(simulationButtonBox);
                numberOfNodesHBox.getChildren().addAll(numberOfNodesText, numberOfNodesTextField);
                container.getChildren().add(numberOfNodesHBox);
            } else {
                System.out.println("HERE!");
                Node n1 = new Node("A", new double[] { 50, 150 }, 50, network);
                Node n2 = new Node("B", new double[] { 100, 150 }, 50, network);
                Node n3 = new Node("C", new double[] { 50, 200 }, 50, network);
                Node n4 = new Node("D", new double[] { 100, 200 }, 50, network);

                Thread n1Thread = new Thread(n1);
                Thread n2Thread = new Thread(n2);
                Thread n3Thread = new Thread(n3);
                Thread n4Thread = new Thread(n4);

                drawNodeNew(n1, pane);
                drawNodeNew(n2, pane);
                drawNodeNew(n3, pane);
                drawNodeNew(n4, pane);

                n1Thread.start();
                n2Thread.start();
                // n3Thread.start();
                // n4Thread.start();

                submitTask(n1, "B", "Wow, routing works!", 3000);
            }
            System.out.println("Selected " + option);
        };

        randomRadio.setOnAction(radioHandler);
        manualRadio.setOnAction(radioHandler);
        fixedLongRouteRadio.setOnAction(radioHandler);
        container.getChildren().add(radioHBox);
        container.getChildren().add(nodeVBox);
        container.getChildren().add(paneBox);
        // container.getChildren().add(startSimulationButton);

        stage.show();
    }

    public int getRandomNumber(int min, int max) {
        return (int) ((Math.random() * (max - min)) + min);
    }

    EventHandler<ActionEvent> stopRandomSimulation = actionEvent -> {
        simulationRunning = false;
        for (Thread thread : simulationThreads) {
            thread.interrupt();
        }
        for (Node node : nodeList) {
            node.reset();
        }
        ;
    };

    EventHandler<ActionEvent> startRandomSimulation = actionEvent -> {
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (Node node : nodeList) {
                    Thread thread = new Thread(node);
                    simulationThreads.add(thread);
                    thread.start();
                }
                simulationRunning = true;
                while (simulationRunning) {
                    Integer index = ThreadLocalRandom.current().nextInt(0, nodeList.size());
                    Integer receiverIndex = ThreadLocalRandom.current().nextInt(0, nodeList.size());
                    Node sendNode = nodeList.get(index);
                    Node receiverNode = nodeList.get(receiverIndex);
                    // Get random node in range....
                    submitTask(sendNode, receiverNode.id, "This is a routing protocol! Amazing!",
                            ThreadLocalRandom.current().nextInt(10, 500));
                    try {
                        Thread.sleep(ThreadLocalRandom.current().nextLong(500, 1000));
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }).start();
    };

    public Optional<Node> getRandomNodeInRange(Node sender) {
        List<Node> inRange = nodeList.stream()
                .filter(n -> network.nodeWithinNodeRange(sender, n) && !Objects.equals(sender.id, n.id)).toList();
        if (!inRange.isEmpty()) {
            Integer randomIndex = ThreadLocalRandom.current().nextInt(0, inRange.size());
            return Optional.of(inRange.get(randomIndex));
        }
        return Optional.empty();
    }

    public static String getNthLetter(int n) {
        int asciiValue = 'A' + n - 1;
        String nthLetter = Character.toString((char) asciiValue);
        return nthLetter;
    }

    static void submitTask(Node node, String receiver, String data, Integer delay) {
        TimerTask task = new TimerTask() {
            @Override
            public void run() {
                try {
                    node.sendDSR(receiver, data);
                } catch (Exception e) {
                    // e.printStackTrace();
                    // System.out.println("EXCEPTION");
                    // Reschedule because node was in QUIET state
                    submitTask(node, receiver, data, delay + 200);
                }
            }
        };
        Timer timer = new Timer();
        timer.schedule(task, delay);
    }

    void runSimulation() {
        Thread networkThread = new Thread(network, "Network thread");
        networkThread.start();
        for (Node n : nodeList) {
            System.out.println(n.toString());
            Thread thread = new Thread(n, n.id);
            thread.start();
        }
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        submitTask(nodeList.get(2), "Node 3", "Helloo!", 100);
        System.out.println("Node at index 2: " + nodeList.get(2).id);
        submitTask(nodeList.get(0), "Node 1", "Goodbye!", 600);
        // while (true) {
        // System.out.println(nodeList.get(0).getCurrent_state() + " " +
        // nodeList.get(1).getCurrent_state() + " " + nodeList.get(2).getCurrent_state()
        // + " " + nodeList.get(3).getCurrent_state());
        // try {
        // Thread.sleep(50);
        // } catch (InterruptedException e) {
        // throw new RuntimeException(e);
        // }
        // }
    }

    void updateNodeInList(Node node) {
        for (int i = 0; i < nodeList.size(); i++) {
            Node lNode = nodeList.get(i);
            if (lNode.id.equals(node.id)) {
                nodeList.set(i, node);
                Circle circle = circleMap.getOrDefault(lNode.id, new Circle());
                circle.setLayoutX(lNode.coordinate[0]);
                circle.setLayoutY(lNode.coordinate[1]);
                circleMap.put(lNode.id, circle);

                Circle range = rangeMap.getOrDefault(lNode.id, new Circle());
                range.setLayoutX(lNode.coordinate[0]);
                range.setLayoutY(lNode.coordinate[1]);
                range.setRadius(lNode.range);
                range.setFill(Color.TRANSPARENT);
                range.setStroke(Color.RED);
                range.setStrokeWidth(1);
                rangeMap.put(lNode.id, range);
                return;
            }
        }
    }

    void showTransmission(Node n, String message) {
        Duration duration = Duration.seconds(2);
        int finalRadius = (int) n.range;
        Circle animationBasis = new Circle();
        animationBasis.setFill(Color.TRANSPARENT);
        animationBasis.setLayoutX(n.coordinate[0]);
        animationBasis.setLayoutY(n.coordinate[1]);
        animationBasis.setRadius(4);
        animationBasis.setStroke(Color.RED);
        animationBasis.setStrokeWidth(1);
        pane.getChildren().add(animationBasis);
        Text text = new Text(message);
        text.setLayoutX(n.coordinate[0]);
        text.setLayoutY(n.coordinate[1]);
        pane.getChildren().add(text);

        Timeline timeline = new Timeline();

        for (int frame = 0; frame <= 100; frame++) {
            Duration frameDuration = Duration.millis((frame * duration.toMillis()) / 100);
            double currentRadius = (frame / (double) 100) * finalRadius;

            int finalFrame = frame;
            KeyFrame keyFrame = new KeyFrame(frameDuration, event -> {
                animationBasis.setRadius(currentRadius);
                double textAngle = (finalFrame / (double) 100) * 360;
                double textXEdge = n.coordinate[0] + currentRadius; // * Math.cos(Math.toRadians(textAngle));
                double textYEdge = n.coordinate[1] + currentRadius; // * Math.sin(Math.toRadians(textAngle));
                text.setLayoutX(textXEdge);
                text.setLayoutY(textYEdge);
            });

            timeline.getKeyFrames().add(keyFrame);
        }
        timeline.setCycleCount(1);
        timeline.play();
        // EventHandler onFinished = event ->
        // {pane.getChildren().remove(animationBasis);};
        // timeline.setOnFinished(onFinished);
        EventHandler onFinished = event -> {
            pane.getChildren().remove(text);
            pane.getChildren().remove(animationBasis);
        };

        timeline.setOnFinished(onFinished);
    }

    void drawNodeNew(Node n, Pane pane) {
        Circle range = new Circle();
        range.setRadius(n.range);
        range.setLayoutX(n.coordinate[0]);
        range.setLayoutY(n.coordinate[1]);
        range.setFill(Color.TRANSPARENT);
        range.setStroke(Color.RED);
        range.setStrokeWidth(1);
        rangeMap.put(n.id, range);

        Text text = new Text();
        text.setText(n.id);
        text.setLayoutX(n.coordinate[0] - 4);
        text.setLayoutY(n.coordinate[1] + 4);

        pane.getChildren().add(range);
        pane.getChildren().add(text);
    }

    private void drawCircle(double finalRadius, Node node) {
        Integer fc = framecountMap.getOrDefault(node.id, 0);
        double scale = (fc / (double) 100) * (finalRadius - 1) + 1;

        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.TRANSPARENT);
        gc.setStroke(Color.RED);

        gc.strokeOval(node.coordinate[0] - scale, node.coordinate[1] - scale, 2 * scale * 1, 2 * scale * 1);
        framecountMap.put(node.id, (fc + 1));
    }

    void sameTime() {
        ParallelTransition transition = new ParallelTransition();

        for (Node n : nodeList) {
            transition.getChildren().add(transmitAnimation(n));
        }

        transition.play();
    }

    Timeline transmitAnimation(Node node) {

        Timeline timeline = new Timeline();

        for (int frame = 0; frame < 100; frame++) {
            Duration frameDuration = Duration.millis((frame * ANIMATION_DURATION.toMillis()) / 100.0);
            timeline.getKeyFrames().add(new KeyFrame(frameDuration, e -> drawCircle(node.range, node)));

        }
        framecountMap.clear();

        timeline.setCycleCount(1);

        return timeline;
    }

    EventHandler<ActionEvent> addManualNodeHandler = actionEvent -> {
        Node node = new Node("Node " + nodeList.size(), new double[] { 200, 200 }, 20, network);
        nodeList.add(node);

        Object button = actionEvent.getSource();
        Pane pane = null;
        if (button instanceof Button) {
            pane = (Pane) ((Button) button).getUserData();
        }

        TextField nodeNameTextField = new TextField(node.id);
        TextField transmissionRangeTextField = new TextField("20");
        Pane finalPane = pane;

        drawNodeNew(node, finalPane);

        transmissionRangeTextField.textProperty().addListener((observable, oldValue, newValue) -> {
            if (!newValue.matches("\\d*") && !newValue.equals("")) {
                transmissionRangeTextField.setText(newValue.replaceAll("\\D", ""));
            } else if (!transmissionRangeTextField.getText().equals("")) {
                node.range = Double.parseDouble(transmissionRangeTextField.getText());
                updateNodeInList(node);
            }
        });

        TextField xCoordinateTextField = new TextField(Integer.toString((int) node.coordinate[0]));
        TextField yCoordinateTextField = new TextField(Integer.toString((int) node.coordinate[1]));

        xCoordinateTextField.textProperty().addListener((observable, oldValue, newValue) -> {
            if (!newValue.matches("\\d*") && !newValue.equals("")) {
                xCoordinateTextField.setText(newValue.replaceAll("\\D", ""));
            } else if (!xCoordinateTextField.getText().equals("")) {
                node.coordinate[0] = Double.parseDouble(xCoordinateTextField.getText());
                updateNodeInList(node);
            }
        });

        yCoordinateTextField.textProperty().addListener((observable, oldValue, newValue) -> {
            if (!newValue.matches("\\d*") && !newValue.equals("")) {
                yCoordinateTextField.setText(newValue.replaceAll("\\D", ""));
            } else if (!yCoordinateTextField.getText().equals("")) {
                node.coordinate[1] = Double.parseDouble(yCoordinateTextField.getText());
                updateNodeInList(node);
            }
        });

        Button animationButton = new Button("Transmit");
        Pane finalPane3 = pane;
        EventHandler<ActionEvent> animationEvent = actionEvent1 -> {
            sameTime();
            showTransmission(node, "Message");
        };
        animationButton.setOnAction(animationEvent);

        HBox lineHbox = new HBox();
        lineHbox.getChildren().addAll(nodeNameTextField, transmissionRangeTextField, xCoordinateTextField,
                yCoordinateTextField, animationButton);

        nodeVBox.getChildren().add(lineHbox);

    };

    public static void main(String[] args) {
        launch();
    }
}