module systems.glam.look {
  requires java.net.http;

  requires systems.comodal.json_iterator;

  requires software.sava.core;
  requires software.sava.rpc;
  requires software.sava.solana_programs;
  requires software.sava.solana_web2;
  requires software.sava.anchor_programs;
  requires software.sava.core_services;
  requires software.sava.jetty_services;
  requires software.sava.solana_services;

  requires org.eclipse.jetty.server;
  requires org.eclipse.jetty.http2.server;
  requires org.eclipse.jetty.alpn.server;
  requires org.eclipse.jetty.http3.server;

  exports systems.glam.look;
  exports systems.glam.look.http;
}
