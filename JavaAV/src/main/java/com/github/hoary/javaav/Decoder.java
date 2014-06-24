/*
 * Copyright (C) 2013 Alex Andres
 *
 * This file is part of JavaAV.
 *
 * JavaAV is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published
 * by the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version (subject to the "Classpath"
 * exception as provided in the LICENSE file that accompanied
 * this code).
 *
 * JavaAV is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with JavaAV. If not, see <http://www.gnu.org/licenses/>.
 */

package com.github.hoary.javaav;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.avutil.AVRational;

import java.nio.ByteBuffer;
import java.util.Map;

import static org.bytedeco.javacpp.avcodec.AVCodecContext;
import static org.bytedeco.javacpp.avcodec.AVPacket;
import static org.bytedeco.javacpp.avcodec.AVPicture;
import static org.bytedeco.javacpp.avcodec.av_copy_packet;
import static org.bytedeco.javacpp.avcodec.av_free_packet;
import static org.bytedeco.javacpp.avcodec.avcodec_decode_audio4;
import static org.bytedeco.javacpp.avcodec.avcodec_decode_video2;
import static org.bytedeco.javacpp.avcodec.avcodec_get_frame_defaults;
import static org.bytedeco.javacpp.avcodec.avpicture_alloc;
import static org.bytedeco.javacpp.avcodec.avpicture_free;
import static org.bytedeco.javacpp.avutil.av_frame_get_best_effort_timestamp;
import static org.bytedeco.javacpp.avutil.av_sample_fmt_is_planar;
import static org.bytedeco.javacpp.avutil.av_samples_get_buffer_size;

/**
 * Media decoder that currently supports audio and video decoding. A {@code Decoder}
 * is often used with a complementary {@code Encoder}.
 *
 * @author Alex Andres
 */
public class Decoder extends Coder {

	/** Convert decoded images to this pixel format. Default is BGR24. */
	private PixelFormat pixelFormat = PixelFormat.BGR24;

	/** Picture resampler that is used to convert decoded pictures into desired picture format. */
	private PictureResampler videoResampler;

	/** The decoder picture format */
	private PictureFormat srcPictureFormat;

	/** The desired picture output format */
	private PictureFormat dstPictureFormat;

	/** Output image structure used for resampling. */
	private AVPicture picture;


	/**
	 * Create new {@code Decoder} that decodes media with codec with specified {@code CodecID}.
	 *
	 * @param codecId the codec id.
	 *
	 * @throws JavaAVException if decoder could not be created.
	 */
	public Decoder(CodecID codecId) throws JavaAVException {
		this(codecId, null);
	}

	/**
	 * Create new {@code Decoder} that decodes media with codec with specified
	 * {@code CodecID} and the corresponding codec context. The provided codec
	 * context may be {@code null}. In this case the decoder will create a new
	 * context.
	 *
	 * @param codecId the codec id.
	 * @param avContext the codec context.
	 *
	 * @throws JavaAVException if decoder could not be created.
	 */
	Decoder(CodecID codecId, AVCodecContext avContext) throws JavaAVException {
		super(Codec.getDecoderById(codecId), avContext);
	}

	@Override
	public void open(Map<String, String> options) throws JavaAVException {
		super.open(options);

		if (codec.getType() == MediaType.VIDEO) {
			srcPictureFormat = new PictureFormat(avContext.width(), avContext.height(), PixelFormat.byId(avContext.pix_fmt()));
			dstPictureFormat = new PictureFormat(avContext.width(), avContext.height(), pixelFormat);
		}

		if (codec.canDecode()) {
			// hack to correct wrong frame rates that seem to be generated by some codecs.
			if (avContext.time_base().num() > 1000 && avContext.time_base().den() == 1) {
				avContext.time_base().den(1000);
			}
		}
	}

	@Override
	public void close() {
		if (picture != null) {
			avpicture_free(picture);
			picture = null;
		}

		if (videoResampler != null) {
			videoResampler.close();
			videoResampler = null;
		}

		super.close();
	}

	@Override
	public int getImageWidth() {
		return avContext.width();
	}

	@Override
	public int getImageHeight() {
		return avContext.height();
	}

	@Override
	public int getAudioChannels() {
		return avContext.channels();
	}

	@Override
	public int getSampleRate() {
		return avContext.sample_rate();
	}

	@Override
	public SampleFormat getSampleFormat() {
		return SampleFormat.byId(avContext.sample_fmt());
	}

	@Override
	public PixelFormat getPixelFormat() {
		return PixelFormat.byId(avContext.pix_fmt());
	}

	@Override
	public void setPixelFormat(PixelFormat format) {
		if (format == null)
			return;

		this.pixelFormat = format;
	}

	/**
	 * Decode a media packet with audio samples into an {@code AudioFrame}.
	 *
	 * @param mediaPacket packet with audio samples.
	 *
	 * @return an {@code AudioFrame} with normalized PCM audio samples.
	 *
	 * @throws JavaAVException if audio packet could not be decoded.
	 */
	public AudioFrame decodeAudio(MediaPacket mediaPacket) throws JavaAVException {
		if (state != State.Opened)
			throw new JavaAVException("Could not decode audio, decoder is not opened.");

		if (codec.getType() != MediaType.AUDIO)
			throw new JavaAVException("Could not decode audio, this is a non-audio decoder.");

		if (mediaPacket == null)
			throw new JavaAVException("No audio passed to decode.");

		AudioFrame frame = null;
		ByteBuffer packetData = mediaPacket.getData();

		if (packetData != null) {
			avPacket.data(new BytePointer(packetData));
			avPacket.size(packetData.limit());
		}
		else {
			avPacket.data(null);
			avPacket.size(0);
		}

		while (avPacket.size() > 0) {
			// reset frame values
			avcodec_get_frame_defaults(avFrame);

			int len = avcodec_decode_audio4(avContext, avFrame, gotFrame, avPacket);

			if (len > 0) {
				avPacket.data(avPacket.data().position(len));
				avPacket.size(avPacket.size() - len);
			}

			if (len > 0 && gotFrame[0] != 0) {
				AVRational time_base = avContext.time_base();

				long pts = av_frame_get_best_effort_timestamp(avFrame);
				long timestamp = 1000000L * pts * time_base.num() / time_base.den();

				int sampleFormat = avFrame.format();
				int isPlanar = av_sample_fmt_is_planar(sampleFormat);
				int planes = isPlanar != 0 ? avFrame.channels() : 1;
				int bufferSize = av_samples_get_buffer_size((int[]) null, avContext.channels(), avFrame.nb_samples(), avContext.sample_fmt(), 1) / planes;

				SampleFormat format = SampleFormat.byId(sampleFormat);
				ChannelLayout channelLayout = ChannelLayout.byId(avFrame.channel_layout());

				AudioFormat audioFormat = new AudioFormat(format, channelLayout, avFrame.channels(), avFrame.sample_rate());

				frame = new AudioFrame(audioFormat, avFrame.nb_samples());
				frame.setKeyFrame(avFrame.key_frame() != 0);
				frame.setTimestamp(timestamp);

				for (int i = 0; i < planes; i++) {
					BytePointer pointer = avFrame.data(i).capacity(bufferSize);
					ByteBuffer buffer = pointer.asBuffer();
					buffer.position(0);

					frame.getPlane(i).asByteBuffer().put(buffer);
				}
			}
			else {
				break;
			}
		}

		av_free_packet(avPacket);

		return frame;
	}

	/**
	 * Decode a media packet with video frame data into a {@code VideoFrame}.
	 *
	 * @param mediaPacket packet with a video frame.
	 *
	 * @return a {@code VideoFrame} in which the decoded video frame will be stored.
	 *
	 * @throws JavaAVException if video packet could not be decoded.
	 */
	public VideoFrame decodeVideo(MediaPacket mediaPacket) throws JavaAVException {
		if (state != State.Opened)
			throw new JavaAVException("Could not decode video, decoder is not opened.");

		if (codec.getType() != MediaType.VIDEO)
			throw new JavaAVException("Could not decode video, this is a non-video decoder.");

		if (mediaPacket == null)
			throw new JavaAVException("No data passed to decode.");

		VideoFrame frame = new VideoFrame();
		AVPacket mPacket = mediaPacket.getAVPacket();

		if (mPacket != null) {
			// re-use packet for better timestamp estimation
			av_copy_packet(avPacket, mPacket);
		}
		else {
			ByteBuffer packetData = mediaPacket.getData();

			if (packetData != null && packetData.limit() > 0) {
				avPacket.data(new BytePointer(packetData));
				avPacket.size(packetData.limit());
			}
		}

		// reset frame parameters
		avcodec_get_frame_defaults(avFrame);

		int len = avcodec_decode_video2(avContext, avFrame, gotFrame, avPacket);

		if (len >= 0 && gotFrame[0] != 0) {
			long pts = av_frame_get_best_effort_timestamp(avFrame);
			AVRational time_base = avContext.time_base();
			long timestamp = 1000000L * pts * time_base.num() / time_base.den() * 2;

			int width = avContext.width();
			int height = avContext.height();
			int channels;
			BytePointer data;

			if (videoResampler == null) {
				if (!srcPictureFormat.isValid())
					srcPictureFormat = new PictureFormat(width, height, PixelFormat.byId(avContext.pix_fmt()));
				if (!dstPictureFormat.isValid())
					dstPictureFormat = new PictureFormat(width, height, pixelFormat);

				videoResampler = new PictureResampler();
				videoResampler.open(srcPictureFormat, dstPictureFormat);
			}
			if (!srcPictureFormat.equals(dstPictureFormat)) {
				if (picture == null)
					createImageBuffer();

				videoResampler.resample(new AVPicture(avFrame), picture);

				channels = picture.linesize(0) / width;
				data = picture.data(0);
			}
			else {
				channels = avFrame.linesize(0) / width;
				data = avFrame.data(0);
			}
			// set buffer parameters to allow correct usage
			data.position(0).capacity(width * height * channels);

			frame = new VideoFrame(data.asByteBuffer(), width, height, pixelFormat);
			frame.setKeyFrame(avFrame.key_frame() != 0);
			frame.setTimestamp(timestamp);
		}
		else if ((avPacket.data() == null || (mPacket != null && mPacket.data() == null)) && avPacket.size() == 0) {
			// decoding error or all buffered frames decoded
			frame = null;
		}

		av_free_packet(avPacket);

		return frame;
	}

	/**
	 * Create resampled picture buffer. This is only needed if the decoded picture format
	 * differs from the desired format.
	 *
	 * @throws JavaAVException if picture buffer could not be allocated.
	 */
	private void createImageBuffer() throws JavaAVException {
		int format = pixelFormat.value();
		int width = avContext.width();
		int height = avContext.height();

		picture = new AVPicture();

		if (avpicture_alloc(picture, format, width, height) < 0)
			throw new JavaAVException("Could not allocate picture.");
	}

}
