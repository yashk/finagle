package com.twitter.finagle.thrift

import org.jboss.netty.channel.ChannelPipelineFactory
import com.twitter.finagle.{Codec, CodecFactory, ClientCodecConfig}
import org.apache.thrift.protocol.{TBinaryProtocol, TProtocolFactory}

/**
 * ThriftClientFramedCodec implements a buffered thrift transport that
 * supports upgrading in order to provide TraceContexts across
 * requests.
 */
object ThriftClientBufferedCodec {
  /**
   * Create a [[com.twitter.finagle.thrift.ThriftClientBufferedCodecFactory]]
   */
  def apply() = new ThriftClientBufferedCodecFactory
}

class ThriftClientBufferedCodecFactory extends
  CodecFactory[ThriftClientRequest, Array[Byte]]#Client
{
  /**
   * Create a [[com.twitter.finagle.thrift.ThriftClientBufferedCodec]]
   * with a default TBinaryProtocol.
   */
  def apply(config: ClientCodecConfig) = {
    new ThriftClientBufferedCodec(new TBinaryProtocol.Factory(), config)
  }
}

class ThriftClientBufferedCodec(protocolFactory: TProtocolFactory, config: ClientCodecConfig)
  extends ThriftClientFramedCodec(protocolFactory, config)
{
  override def pipelineFactory = {
    val framedPipelineFactory = super.pipelineFactory

    new ChannelPipelineFactory {
      def getPipeline() = {
        val pipeline = framedPipelineFactory.getPipeline
        pipeline.replace(
          "thriftFrameCodec", "thriftBufferDecoder",
          new ThriftBufferDecoder(protocolFactory))
        pipeline
      }
    }
  }
}

