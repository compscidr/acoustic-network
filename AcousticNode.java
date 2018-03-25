import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import javax.sound.sampled.*;
import java.util.*;

class AcousticNode
{
	//400 = 8,9,10	(>7) SYNC
	//600 = 5,6		(<7 && > 3) ZERO
	//1200 = 2,3	(<3) ONE
	
	private static final double ONE = 1200;
	private static final double ZERO = 600;
	private static final double SYNC = 400;
	private static final double DURATION = 1;		//less than one and we loose sync signals
	private static final int MAX_BUFFER = 1024;
	
	private byte[] internalBuffer = new byte[MAX_BUFFER];
	private boolean running = false;
	
	public static void main(String[] args) throws Exception
	{
		AcousticNode n = new AcousticNode();
		n.captureAudio();
		n.sendFrame("test".getBytes(), 5);
		
		n.stopListening();
	}

	/*
	 * Encapsulates the data in an ethernet frame and sends it one byte
	 * at a time
	 * 
	 * Note: bits within an octet (byte) are lsb first
	 * Data is most significant octet first
	 */
	public void sendFrame(byte[] data, int size)
	{
		//1 octet pre-amble (eth uses 7)
		sendByte((byte)0x55);
		
		//1 octet start frame delimiter
		sendByte((byte)0xD5);
		
		//6 octet mac dest
		//skip - just broadcast to all for now
		
		//6 octet src
		//skip - just broadcast to all for now
		
		//1 octet length (max size = 2^8 = 256-1 octets)
		sendByte((byte)size);
		
		for(int i = 0; i < size; i++)
		{
			sendByte(data[i]);
		}
		
		//compute crc & send
	}

	/*
	 * Sends a single byte of data, one bit at a time
	 * lsb first
	 */
	public void sendByte(byte data)
	{
		byte temp;
		for(byte i=0; i < 8; i++)
		{
			temp = data;
			temp = (byte)(temp >> i);
			temp = (byte)(temp & 1);
			
			sendSync();
			
			if(temp == 0)
				sendZero();
			else
				sendOne();
		}
	}

	private void sendOne()
	{
		ByteArrayOutputStream out = generateTone(ONE,DURATION);	
		playAudio(out);
	}
	
	private void sendZero()
	{
		ByteArrayOutputStream out = generateTone(ZERO,DURATION);
		playAudio(out);
	}
	
	private void sendSync()
	{
		ByteArrayOutputStream out = generateTone(SYNC,DURATION);		
		playAudio(out);
	}
	
	public void stopListening()
	{
		running = false;
	}
	
	//fills in the output buffer with "duration" seconds of the specified tone
	//source: http://stackoverflow.com/questions/2413426/playing-an-arbitrary-tone-with-android
	private ByteArrayOutputStream generateTone(double freqOfTone, double duration)
	{
		
		final AudioFormat format = getFormat();
		int numSamples = (int)(duration * format.getSampleRate());
		
		byte[] buffer = new byte[numSamples];
		float angle = 0;
		for(int i=0; i < buffer.length; i++)
		{
			float angular_frequency = (float)(2*Math.PI) * (float)freqOfTone / (float)format.getSampleRate();
			buffer[i] = (byte)(Byte.MAX_VALUE * ((float) Math.sin(angle)));
			angle += angular_frequency;
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(buffer, 0, buffer.length);
		return out;
	}
	
	
	//source: http://edenti.deis.unibo.it/utils/Java-tips/Capturing%20Audio%20with%20Java%20Sound%20API.txt
	private void captureAudio() 
	{
		try 
		{
			final AudioFormat format = getFormat();
			DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
			final TargetDataLine line = (TargetDataLine)
			AudioSystem.getLine(info);
			line.open(format);
			line.start();
			
			Runnable runner = new Runnable() 
			{
				int bufferSize = (int)format.getSampleRate() * format.getFrameSize();
				byte buffer[] = new byte[bufferSize];
		 
				public void run() 
				{
					ByteArrayOutputStream out = new ByteArrayOutputStream();
					running = true;
					try 
					{
						while (running) 
						{
							int count = line.read(buffer, 0, buffer.length);
							if (count > 0) 
							{
								//out.write(buffer, 0, count);
								
								//process the read
								int pos_index = -1; // index of first positive encountered
								int neg_index = -1; // index of first neg encountered
								
								//run through the observed data and count the half period distances
								//counting the frequency of each observed half period distance
								Map<Integer, Integer> m = new HashMap<Integer, Integer>();
								for(int i = 0; i < count; i++)
								{
									if((buffer[i] > 0) && (pos_index == -1))
										pos_index = i;
									if((buffer[i] < 0) && (neg_index == -1))
										neg_index = i;
									
									if((pos_index >= 0 && neg_index >= 0))
									{
										Integer distance = Math.abs(pos_index - neg_index);
										//System.out.format("%d ", distance);
										pos_index = -1;
										neg_index = -1;
										Integer freq = m.get(distance);
										m.put(distance, (freq == null) ? 1 : freq + 1);
									}
								}
								
								//determine the most frequently occuring half period distance in the observed time frame
								int max_value = -1;
								int max_key = -1;
								for (Map.Entry<Integer, Integer> e : m.entrySet())
								{
									if(e.getValue() > max_value)
									{
										max_value = e.getValue();
										max_key = e.getKey();
									}
										
									//System.out.println(e.getKey() + ": " + e.getValue());
								}
								
								if(max_key < 3)
									System.out.println("SIGNAL: ONE");
								else if(max_key > 7)
									System.out.println("SIGNAL: SYNC");
								else if(max_key != -1)
									System.out.println("SIGNAL: ZERO");
								else
									System.out.println("NOISE");
								
								System.out.println("---------------");
								//System.out.println(m);
							}
					}
					out.close();
					} 
					catch (IOException e) 
					{
						System.err.println("I/O problems: " + e);
						System.exit(-1);
					}
				}
			};
			Thread captureThread = new Thread(runner);
			captureThread.start();
		} 
		catch (LineUnavailableException e) 
		{
			System.err.println("Line unavailable: " + e);
			System.exit(-2);
		}
	}
	
	//source: http://edenti.deis.unibo.it/utils/Java-tips/Capturing%20Audio%20with%20Java%20Sound%20API.txt
	//plays the output buffer until empty in "blocking mode"
	private void playAudio(ByteArrayOutputStream out) 
	{
		try 
		{
			byte audio[] = out.toByteArray();
			InputStream input = new ByteArrayInputStream(audio);
			final AudioFormat format = getFormat();
			final AudioInputStream ais = new AudioInputStream(input, format, audio.length / format.getFrameSize());
			DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
			final SourceDataLine line = (SourceDataLine)AudioSystem.getLine(info);
			line.open(format);
			line.start();

			int bufferSize = (int) format.getSampleRate() * format.getFrameSize();
			byte buffer[] = new byte[bufferSize];

			try 
			{
				int count;
				while ((count = ais.read(buffer, 0, buffer.length)) != -1)
				{
					if (count > 0) 
					{
						line.write(buffer, 0, count);
					}
				}
				line.drain();
				line.close();
			} 
			catch (IOException e) 
			{
				System.err.println("I/O problems: " + e);
				System.exit(-3);
			}
		} 
		catch (LineUnavailableException e) 
		{
			System.err.println("Line unavailable: " + e);
			System.exit(-4);
		} 
	}
	
	//source: http://edenti.deis.unibo.it/utils/Java-tips/Capturing%20Audio%20with%20Java%20Sound%20API.txt
	private AudioFormat getFormat()
	{
		float sampleRate = 8000;
		int sampleSizeInBits = 8;
		int channels = 1;
		boolean signed = true;
		boolean bigEndian = true;
		
		//construct linear PCM encoding - look later into mu encoding and anything more complicated
		return new AudioFormat(sampleRate, sampleSizeInBits, channels, signed, bigEndian);
	}
}
