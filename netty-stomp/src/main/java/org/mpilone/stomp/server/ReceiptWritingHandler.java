package org.mpilone.stomp.server;

import org.mpilone.stomp.Frame;
import org.mpilone.stomp.FrameBuilder;
import org.mpilone.stomp.Headers;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 *
 * @author mpilone
 */
public class ReceiptWritingHandler extends SimpleChannelInboundHandler<Frame> {

  public ReceiptWritingHandler() {
    super(Frame.class, true);
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, Frame frame) throws
      Exception {

    String receiptId = frame.getHeaders().get(Headers.RECEIPT);
    if (receiptId != null) {
      Frame resp = FrameBuilder.receipt(receiptId).build();
      ctx.writeAndFlush(resp);
    }

    ctx.fireChannelRead(frame);
  }

}