package meicotools.server;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public class HttpServerMain {
    public static void main(String[] args) throws Exception {
        int port = Integer.getInteger("port", 8080);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/convert", new ConvertHandler());
        server.createContext("/perform", new PerformHandler());
        server.setExecutor(Executors.newFixedThreadPool(4));
        System.out.println("meico-tools listening on http://localhost:" + port);
        server.start();
    }
}
