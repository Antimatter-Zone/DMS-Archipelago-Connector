package com.antimatterzone.apconnector;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/** GUI is standalone; --stdio is a protected parent/child pipe contract, not a logging command. */
public final class Main {
    public static void main(String[] args) {
        try {
            if(args.length==1 && args[0].equals("--stdio")) {stdio();return;}
            if(args.length==1 && args[0].equals("--self-test")) {
                if(Runtime.version().feature()!=25) throw new IllegalStateException();
                Json.MAPPER.readTree("{}");java.net.http.HttpClient.newHttpClient();
                System.out.println("DMS Archipelago Connector: Java 25, JSON, HTTP and desktop modules available.");return;
            }
            if(args.length!=0) throw new IllegalArgumentException();
            SwingUtilities.invokeLater(Main::showForm);
        } catch(Exception error) {
            // Exception strings can contain URI input, JSON snippets or credentials: never render them.
            System.err.println("Connector could not start. Check the selected managed instance and connection configuration.");
            System.exit(1);
        }
    }
    static String readBoundedLine(InputStream stream, int maximum) throws IOException {
        ByteArrayOutputStream data=new ByteArrayOutputStream();int next;
        while((next=stream.read())!=-1 && next!='\n') {if(data.size()>=maximum) throw new IOException("Input limit exceeded.");data.write(next);}
        if(next==-1 && data.size()==0) return null;
        return data.toString(StandardCharsets.UTF_8).stripTrailing();
    }
    private static void stdio() throws Exception {
        String line=readBoundedLine(System.in,16384);if(line==null) throw new IOException();
        ConnectionConfig config=ConnectionConfig.fromJson(Json.MAPPER.readTree(line));line=null;
        try(Connector connector=new Connector(config)) {
            Runtime.getRuntime().addShutdownHook(new Thread(connector::close));connector.connect();
            // Deliberately contains the launch-scoped LOOPBACK bearer, never the AP password.
            // Parent MUST pipe stdout and must not inherit a terminal or capture it as a diagnostic log.
            System.out.println(Json.MAPPER.writeValueAsString(connector.gameConfiguration()));System.out.flush();
            while((line=readBoundedLine(System.in,256))!=null && !line.equals("STOP")) {
                if(line.equals("STATUS")) {System.out.println(Json.MAPPER.writeValueAsString(connector.status()));System.out.flush();}
            }
        }
    }
    private static void showForm() {
        JFrame frame=new JFrame("DMS Archipelago Connector — development candidate");frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JPanel content=new JPanel(new GridBagLayout());content.setBorder(BorderFactory.createEmptyBorder(20,20,20,20));
        JTextField server=new JTextField("wss://",36),slot=new JTextField(36),instance=new JTextField(36);
        JPasswordField password=new JPasswordField(36);
        JLabel status=new JLabel("Standalone Game Dev Tycoon. No AMZ account required.");
        JLabel warning=new JLabel("Farmer is disabled pending runtime/contract qualification.");
        JButton connect=new JButton("Connect"),disconnect=new JButton("Disconnect");disconnect.setEnabled(false);
        JTextArea instructions=new JTextArea(4,44);instructions.setEditable(false);instructions.setLineWrap(true);instructions.setWrapStyleWord(true);
        GridBagConstraints cell=new GridBagConstraints();cell.insets=new Insets(5,5,5,5);cell.anchor=GridBagConstraints.WEST;
        String[] labels={"AP server WS/WSS address","Slot / connection alias","Slot password","Managed game instance"};
        JComponent[] inputs={server,slot,password,instance};
        for(int row=0;row<labels.length;row++){cell.gridy=row;cell.gridx=0;content.add(new JLabel(labels[row]),cell);cell.gridx=1;content.add(inputs[row],cell);}
        JButton choose=new JButton("Choose folder");cell.gridx=2;content.add(choose,cell);
        choose.addActionListener(event->{JFileChooser picker=new JFileChooser();picker.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);if(picker.showOpenDialog(frame)==JFileChooser.APPROVE_OPTION)instance.setText(picker.getSelectedFile().getAbsolutePath());});
        cell.gridx=0;cell.gridy=4;cell.gridwidth=3;content.add(warning,cell);
        cell.gridy=5;content.add(status,cell);cell.gridy=6;JPanel actions=new JPanel();actions.add(connect);actions.add(disconnect);content.add(actions,cell);
        cell.gridy=7;content.add(instructions,cell);
        Connector[] active={null};
        javax.swing.Timer timer=new javax.swing.Timer(1000,event->{if(active[0]!=null){var s=active[0].status();status.setText(Boolean.TRUE.equals(s.get("connected"))?"Connected — team "+s.get("team")+", slot "+s.get("slot")+"; items "+s.get("receivedItems"):"Connection: "+s.get("failure"));}});timer.start();
        connect.addActionListener(event->{
            char[] secret=password.getPassword();
            try {
                Path directory=Path.of(instance.getText());String id="manual-"+Json.hash(directory.toAbsolutePath().normalize().toString()).substring(0,24);
                ConnectionConfig config=new ConnectionConfig(URI.create(server.getText().trim()),"game-dev-tycoon",slot.getText().trim(),new String(secret),directory,id,id,false,null,null);
                if(!config.encrypted() && JOptionPane.showConfirmDialog(frame,"This WS connection is not encrypted. The server password and gameplay traffic can be observed in transit. Continue?","Unencrypted AP connection",JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE)!=JOptionPane.YES_OPTION)return;
                active[0]=new Connector(config);Path launch=active[0].writeLaunchConfiguration();active[0].connect();
                password.setText("");connect.setEnabled(false);disconnect.setEnabled(true);
                instructions.setText("Local launch configuration: "+launch+"\nUse only with the reviewed managed GDT instance. Game installation and save isolation must be verified separately. No base game files are installed by this connector.");
            } catch(Exception error) {if(active[0]!=null){active[0].close();active[0]=null;}status.setText("Cannot connect: verify address, managed instance, and saved identity.");}
            finally {Arrays.fill(secret,'\0');}
        });
        disconnect.addActionListener(event->{if(active[0]!=null){active[0].close();active[0]=null;}connect.setEnabled(true);disconnect.setEnabled(false);status.setText("Disconnected.");});
        frame.addWindowListener(new WindowAdapter(){public void windowClosed(WindowEvent event){timer.stop();if(active[0]!=null)active[0].close();}});
        frame.setContentPane(content);frame.pack();frame.setLocationByPlatform(true);frame.setVisible(true);
    }
    private Main() { }
}
