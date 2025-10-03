package meicotools.server;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;

public class HttpServerMain {
    public static void main(String[] args) throws Exception {
        int port = Integer.getInteger("port", 8080);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/convert", new ConvertHandler());
        server.createContext("/perform", new PerformHandler());
        server.createContext("/modify", new ModifyHandler());
        server.setExecutor(null);
        System.out.println("meico-tools listening on http://localhost:" + port);
        server.start();
    }
}
