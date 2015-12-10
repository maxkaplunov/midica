/*
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. 
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.midica.midi;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.ArrayList;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.SysexMessage;
import javax.sound.midi.Track;

import org.midica.config.Dict;
import org.midica.file.ParseException;

import com.sun.media.sound.MidiUtils;

/**
 * This class analyzes a MIDI sequence and collects information from it.
 * These information can be displayed later by the {@link InfoView}
 * 
 * It also adds marker events to the sequence at each tick where the
 * channel activity changes for at least one channel.
 * 
 * @author Jan Trukenmüller
 */
public class SequenceAnalyzer {
	
	public static final byte NOTE_HISTORY_BUFFER_SIZE_PAST   = 5;
	public static final byte NOTE_HISTORY_BUFFER_SIZE_FUTURE = 3;
	
	private static Sequence sequence = null;
	private static String   fileType = null;
	private static HashMap<String, Object> sequenceInfo = null;
	
	/**                    channel   --  note -- usage count */
	private static TreeMap<Byte, TreeMap<Byte, Integer>> keysByChannel = null;
	
	/**                    channel   --  program number */
	private static TreeMap<Byte, TreeSet<Byte>> programsByChannel = null;
	
	/**                   channel    --     note      --     tick -- on/off */
	private static TreeMap<Byte, TreeMap<Byte, TreeMap<Long, Boolean>>> noteOnOffByChannel = null;
	
	/**                    channel  --   tick    --    note -- volume */
	private static TreeMap<Byte, TreeMap<Long, TreeMap<Byte, Byte>>> noteHistory = null;
	
	/**                    channel  --   tick -- number of keys pressed at this time */
	private static TreeMap<Byte, TreeMap<Long, Integer>> activityByChannel = null;
	
	/**                    tick     --   channel */
	private static TreeMap<Long, TreeSet<Byte>> markers = null;
	
	/**                    tick */
	private static TreeSet<Long> markerTicks = null;
	
	/**
	 * This class is only used statically so a public constructor is not needed.
	 */
	private SequenceAnalyzer() {
	}
	
	/**
	 * Analyzes the given MIDI stream and collects information about it.
	 * Adds marker events for channel activity changes.
	 * 
	 * @param seq  The MIDI sequence to be analyzed.
	 * @param type The file type where the stream originally comes from
	 *             -- **mid** for MIDI or **midica** for MidicaPL.
	 * @throws ParseException if a marker event cannot be created during the postprocessing
	 */
	public static void analyze ( Sequence seq, String type ) throws ParseException {
		sequence = seq;
		fileType = type;
		
		// initialize data structures
		init();
		
		// fill data structures
		parse();
		
		// add statistic information to the data structures
		postprocess();
	}
	
	/**
	 * Returns the information that have been collected while
	 * analyzing the MIDI sequence.
	 * 
	 * If no MIDI stream has been loaded: returns an empty data structure.
	 * 
	 * @return MIDI stream info.
	 */
	public static HashMap<String, Object> getStreamInfo() {
		if ( null == sequenceInfo )
			return new HashMap<String, Object>();
		return sequenceInfo;
	}
	
	/**
	 * Initializes the internal data structures so that they are ready to
	 * be filled with sequence information during the parsing process.
	 */
	private static void init() {
		
		// initialize data structures for the sequence info
		sequenceInfo = new HashMap<String, Object>();
		sequenceInfo.put( "resolution",       sequence.getResolution()        );
		sequenceInfo.put( "used_channels",    new TreeSet<Integer>()          );
		sequenceInfo.put( "used_banks",       new TreeSet<String>()           );
		sequenceInfo.put( "banks_by_channel", new TreeMap<Integer, String>()  );
		sequenceInfo.put( "tempo_mpq",        new TreeMap<Long, Integer>()    );
		sequenceInfo.put( "tempo_bpm",        new TreeMap<Long, Integer>()    );
		sequenceInfo.put( "parser_type",      fileType                        );
		sequenceInfo.put( "ticks",            sequence.getTickLength()        );
		keysByChannel      = new TreeMap<Byte, TreeMap<Byte, Integer>>();
		programsByChannel  = new TreeMap<Byte, TreeSet<Byte>>();
		sequenceInfo.put( "keys_by_channel",     keysByChannel     );
		sequenceInfo.put( "programs_by_channel", programsByChannel );
		long microseconds = sequence.getMicrosecondLength();
		String time       = MidiDevices.microsecondsToTimeString( microseconds );
		sequenceInfo.put( "time_length", time );
		
		// init data structures for the channel activity
		activityByChannel  = new TreeMap<Byte, TreeMap<Long, Integer>>();
		noteOnOffByChannel = new TreeMap<Byte, TreeMap<Byte, TreeMap<Long, Boolean>>>();
		markerTicks        = new TreeSet<Long>();
		markers            = new TreeMap<Long, TreeSet<Byte>>();
		
		// init data structures for the note history
		noteHistory = new TreeMap<Byte, TreeMap<Long, TreeMap<Byte, Byte>>>();
		for ( byte channel = 0; channel < 16; channel++ )
			noteHistory.put( channel, new TreeMap<Long, TreeMap<Byte, Byte>>() );
	}
	
	/**
	 * Parses the MIDI sequence track by track and event by event and
	 * collects information.
	 */
	private static void parse() {
		
		// Analyze the ORIGINAL sequence for general statistics.
		for ( Track t : sequence.getTracks() ) {
			for ( int i=0; i < t.size(); i++ ) {
				MidiEvent   event = t.get( i );
				long        tick  = event.getTick();
				MidiMessage msg   = event.getMessage();
				
				if ( msg instanceof MetaMessage ) {
					processMetaMessage( (MetaMessage) msg, tick );
				}
				else if ( msg instanceof ShortMessage ) {
					processShortMessageGeneral( (ShortMessage) msg, tick );
				}
				else if ( msg instanceof SysexMessage ) {
					processSysexMessage( (SysexMessage) msg, tick );
				}
				else {
				}
			}
		}
		
		// Analyze the CREATED sequence for channel activity and note history.
		// In this sequence tracks match channels. So we know that we will
		// process the note-related events in the right order.
		for ( Track t : SequenceCreator.getSequence().getTracks() ) {
			for ( int i=0; i < t.size(); i++ ) {
				MidiEvent   event = t.get( i );
				long        tick  = event.getTick();
				MidiMessage msg   = event.getMessage();
				
				if ( msg instanceof ShortMessage ) {
					processShortMessage( (ShortMessage) msg, tick );
				}
			}
		}
	}
	
	/**
	 * Retrieves general information from short messages.
	 * 
	 * @param msg   Short message
	 * @param tick  Tickstamp
	 */
	private static void processShortMessageGeneral( ShortMessage msg, long tick ) {
		int cmd     = msg.getCommand();
		int channel = msg.getChannel();
		
		// PROGRAM CHANGE
		if ( ShortMessage.PROGRAM_CHANGE == cmd ) {
			int instrNum = msg.getData1();
			TreeSet<Byte> programs = programsByChannel.get( (byte) channel );
			if ( null == programs ) {
				programs = new TreeSet<Byte>();
				programsByChannel.put( (byte) channel, programs );
			}
			programs.add( (byte) instrNum );
		}
		
		// another channel command
		else {
			
		}
		
		// TODO: collect event information
	}
	
	/**
	 * Retrieves note-specific information from short messages.
	 * 
	 * @param msg   Short message
	 * @param tick  Tickstamp
	 */
	private static void processShortMessage( ShortMessage msg, long tick ) {
		int cmd     = msg.getCommand();
		int channel = msg.getChannel();
		
		// NOTE ON or OFF
		if ( ShortMessage.NOTE_ON == cmd ) {
			int note    = msg.getData1();
			int volume  = msg.getData2();
			
			// ON
			if ( volume > 0 )
				addNoteOn( tick, (byte) channel, (byte) note, (byte) volume );
			else
				addNoteOff( tick, (byte) channel, (byte) note );
		}
		
		// NOTE OFF
		else if ( ShortMessage.NOTE_OFF == cmd ) {
			int note = msg.getData1();
			addNoteOff( tick, (byte) channel, (byte) note );
		}
	}
	
	/**
	 * Retrieves information from meta messages.
	 * 
	 * @param msg   Meta message
	 * @param tick  Tickstamp
	 */
	private static void processMetaMessage( MetaMessage msg, long tick ) {
		int    type    = msg.getType();
		int    length  = msg.getLength();
		byte[] message = msg.getMessage();
		
		// TEMPO
		if ( MidiListener.META_SET_TEMPO == type ) {
			int mpq = MidiUtils.getTempoMPQ( msg );
			int bpm = (int) MidiUtils.convertTempo( mpq );
			TreeMap<Long, Integer> tempoMpq = (TreeMap<Long, Integer>) sequenceInfo.get( "tempo_mpq" );
			tempoMpq.put( tick, mpq );
			TreeMap<Long, Integer> tempoBpm = (TreeMap<Long, Integer>) sequenceInfo.get( "tempo_bpm" );
			tempoBpm.put( tick, bpm );
		}
		
		// TODO: collect event information
	}
	
	/**
	 * Retrieves information from SysEx messages.
	 * 
	 * @param msg   SysEx message
	 * @param tick  Tickstamp
	 */
	private static void processSysexMessage(SysexMessage msg, long tick) {
		// TODO: collect event information
	}
	
	/**
	 * Adds a detected **note-on** event to the data structures.
	 * 
	 * @param tick     The tickstamp when this event occurred.
	 * @param channel  The MIDI channel number.
	 * @param note     The note number.
	 * @param volume   The note's velocity.
	 */
	private static void addNoteOn( long tick, byte channel, byte note, byte volume ) {
		
		// totally used keys: keysByChannel
		TreeMap<Byte, Integer> totalKeys = keysByChannel.get( channel );
		if ( null == totalKeys ) {
			totalKeys = new TreeMap<Byte, Integer>();
			keysByChannel.put( channel, totalKeys );
		}
		Integer totalKeyCount = totalKeys.get( note );
		if ( null == totalKeyCount )
			totalKeyCount = 1;
		else
			totalKeyCount++;
		totalKeys.put( note, totalKeyCount );
		
		// note on/off tracking
		TreeMap<Byte, TreeMap<Long, Boolean>> noteTickOnOff = noteOnOffByChannel.get( channel );
		if ( null == noteTickOnOff ) {
			noteTickOnOff = new TreeMap<Byte, TreeMap<Long, Boolean>>();
			noteOnOffByChannel.put( channel, noteTickOnOff );
		}
		TreeMap<Long, Boolean> pressedAtTick = noteTickOnOff.get( note );
		if ( null == pressedAtTick ) {
			pressedAtTick = new TreeMap<Long, Boolean>();
			noteTickOnOff.put( note, pressedAtTick );
		}
		boolean wasPressedBefore = pressedAtTick.containsKey( tick );
		if (wasPressedBefore)
			// Key press and/or release conflict.
			// There was a(nother) ON or OFF event for the same key in the
			// same tick. It's probably depending on the sequencer or
			// synthesizer implementation what happens.
			// Here we just assume that these events will be processed in
			// the same order as we found them.
			wasPressedBefore = pressedAtTick.get( tick );
		if (wasPressedBefore)
			return;
		pressedAtTick.put( tick, true );
		
		// get last channel activity
		int lastChannelActivity = 0;
		TreeMap<Long, Integer> activityAtTick = activityByChannel.get( channel );
		if ( activityAtTick == null ) {
			activityAtTick = new TreeMap<Long, Integer>();
			activityByChannel.put( channel, activityAtTick );
		}
		else {
			Entry<Long, Integer> lastActivity = activityAtTick.floorEntry( tick );
			if ( lastActivity != null )
				lastChannelActivity = lastActivity.getValue();
		}
		activityAtTick.put( tick, lastChannelActivity + 1 );
		
		// note history by channel
		TreeMap<Long, TreeMap<Byte, Byte>> noteHistoryForChannel = noteHistory.get( channel );
		TreeMap<Byte, Byte> noteHistoryAtTick = noteHistoryForChannel.get( tick );
		if ( null == noteHistoryAtTick ) {
			noteHistoryAtTick = new TreeMap<Byte, Byte>();
			noteHistoryForChannel.put( tick, noteHistoryAtTick );
		}
		noteHistoryAtTick.put( note, volume );
		
		// prepare marker event
		markerTicks.add( tick );
	}
	
	/**
	 * Adds a detected **note-off** event to the data structures.
	 * 
	 * @param tick     The tickstamp when this event occurred.
	 * @param channel  The MIDI channel number.
	 * @param note     The note number.
	 */
	private static void addNoteOff( long tick, byte channel, byte note ) {
		
		// check if the released key has been pressed before
		TreeMap<Byte, TreeMap<Long, Boolean>> noteTickOnOff = noteOnOffByChannel.get( channel );
		if ( null == noteTickOnOff ) {
			noteTickOnOff = new TreeMap<Byte, TreeMap<Long, Boolean>>();
			noteOnOffByChannel.put( channel, noteTickOnOff );
		}
		TreeMap<Long, Boolean> pressedAtTick = noteTickOnOff.get( note );
		if ( null == pressedAtTick ) {
			pressedAtTick = new TreeMap<Long, Boolean>();
			noteTickOnOff.put( note, pressedAtTick );
		}
		boolean wasPressedBefore = false;
		Entry<Long, Boolean> wasPressed = pressedAtTick.floorEntry( tick );
		if ( null != wasPressed ) {
			wasPressedBefore = wasPressed.getValue();
		}
		if ( ! wasPressedBefore )
			return;
		
		// mark as released
		pressedAtTick.put( tick, false );
		
		// channel activity
		TreeMap<Long, Integer> activityAtTick = activityByChannel.get( channel );
		if ( null == activityAtTick ) {
			activityAtTick = new TreeMap<Long, Integer>();
			activityByChannel.put( channel, activityAtTick );
		}
		Entry<Long, Integer> lastActivity = activityAtTick.floorEntry( tick );
		if ( null == lastActivity ) {
			// A key was released before it has been pressed for the very first time.
			return;
		}
		
		// decrement activity
		Integer lastActivityCount = lastActivity.getValue();
		if ( lastActivityCount < 1 ) {
			// should never happen
			return;
		}
		activityAtTick.put( tick, lastActivityCount - 1 );
		
		// prepare marker event
		markerTicks.add( tick );
	}
	
	/**
	 * Adds last information to the info data structure about the MIDI stream.
	 * Adds marker events to the stream.
	 * 
	 * @param type "mid" or "midica", depending on the parser class.
	 * @throws ParseException if the marker events cannot be added to the MIDI sequence.
	 */
	private static void postprocess() throws ParseException {
		
		// average, min and max tempo
		TreeMap<Long, Integer> tempoMpq = (TreeMap<Long, Integer>) sequenceInfo.get( "tempo_mpq" );
		TreeMap<Long, Integer> tempoBpm = (TreeMap<Long, Integer>) sequenceInfo.get( "tempo_bpm" );
		long lastTick   = 0;
		int  lastBpm    = MidiDevices.DEFAULT_TEMPO_BPM;
		int  lastMpq    = MidiDevices.DEFAULT_TEMPO_MPQ;
		int  minBpm     = 0;
		int  maxBpm     = 0;
		int  minMpq     = 0;
		int  maxMpq     = 0;
		long bpmProduct = 0;
		long mpqProduct = 0;
		for ( long tick : tempoBpm.keySet() ) {
			
			// average
			int  newBpm   = tempoBpm.get( tick );
			int  newMpq   = tempoMpq.get( tick );
			long tickDiff = tick - lastTick;
			bpmProduct   += tickDiff * lastBpm;
			mpqProduct   += tickDiff * lastMpq;
			lastBpm       = newBpm;
			lastMpq       = newMpq;
			lastTick      = tick;
			
			// min, max
			if ( tick > 0 && 0 == minBpm && 0 == maxBpm ) {
				minBpm = MidiDevices.DEFAULT_TEMPO_BPM;
				maxBpm = MidiDevices.DEFAULT_TEMPO_BPM;
				minMpq = MidiDevices.DEFAULT_TEMPO_MPQ;
				maxMpq = MidiDevices.DEFAULT_TEMPO_MPQ;
			}
			if ( 0 == minBpm || minBpm > newBpm )
				minBpm = newBpm;
			if ( 0 == maxBpm || maxBpm < newBpm )
				maxBpm = newBpm;
			if ( 0 == minMpq || minMpq > newMpq )
				minMpq = newMpq;
			if ( 0 == maxMpq || maxMpq < newMpq )
				maxMpq = newMpq;
		}
		long tickLength = (Long) sequenceInfo.get( "ticks" );
		long tickDiff = tickLength - lastTick;
		bpmProduct   += tickDiff * lastBpm;
		mpqProduct   += tickDiff * lastMpq;
		if ( 0 == minBpm )
			minBpm = lastBpm;
		if ( 0 == maxBpm )
			maxBpm = lastBpm;
		if ( 0 == minMpq )
			minMpq = lastMpq;
		if ( 0 == maxMpq )
			maxMpq = lastMpq;
		double avgBpm = (double) bpmProduct / tickLength;
		double avgMpq = (double) mpqProduct / tickLength;
		sequenceInfo.put( "tempo_bpm_avg", String.format("%.2f", avgBpm) );
		sequenceInfo.put( "tempo_bpm_min", Integer.toString(minBpm) );
		sequenceInfo.put( "tempo_bpm_max", Integer.toString(maxBpm) );
		sequenceInfo.put( "tempo_mpq_avg", String.format("%.1f", avgMpq) );
		sequenceInfo.put( "tempo_mpq_min", Integer.toString(minMpq) );
		sequenceInfo.put( "tempo_mpq_max", Integer.toString(maxMpq) );
		
		// markers
		for ( long tick : markerTicks ) {
			
			// walk through all channels that have any activity IN ANY TICK
			for ( byte channel : activityByChannel.keySet() ) {
				
				boolean activityChanged   = false;
				boolean historyChanged    = false;
				boolean instrumentChanged = false;
				
				// initiate the channel byte for that tick
				TreeSet<Byte> channelsAtTick = markers.get( tick );
				if ( null == channelsAtTick ) {
					channelsAtTick = new TreeSet<Byte>();
					markers.put( tick, channelsAtTick );
				}
				
				// is there any channel activity at the current tick?
				if ( activityByChannel.get(channel).containsKey(tick) ) {
					activityChanged = true;
					
					// is at least one of the channel events a NOTE-ON?
					TreeMap<Byte, TreeMap<Long, Boolean>>    noteTickOnOff    = noteOnOffByChannel.get( channel );
					Set<Entry<Byte, TreeMap<Long, Boolean>>> noteTickOnOffSet = noteTickOnOff.entrySet();
					for ( Entry<Byte, TreeMap<Long, Boolean>> noteTickOnOffEntry : noteTickOnOffSet ) {
						TreeMap<Long, Boolean> tickOnOff = noteTickOnOffEntry.getValue();
						if ( null == tickOnOff )
							continue;
						Boolean onOff = tickOnOff.get( tick );
						if ( null == onOff )
							continue;
						if (onOff) {
							historyChanged = true;
							break;
						}
					}
					
					// apply bitmasks to the channel byte
					if (activityChanged)
						channel |= MidiListener.MARKER_BITMASK_ACTIVITY;
					if (historyChanged)
						channel |= MidiListener.MARKER_BITMASK_HISTORY;
				}
				
				// TODO: is there an instrument change at the current tick?
//				if ( ... ) {
//					...
//					if (instrumentChanged)
//						channel |= MidiListener.MARKER_BITMASK_INSTRUMENT;
//				}
				
				// add the channel to the activity change marker
				if ( activityChanged || historyChanged || instrumentChanged )
					channelsAtTick.add( channel );
			}
		}
		try {
			SequenceCreator.addMarkers( markers );
		}
		catch ( InvalidMidiDataException e ) {
			throw new ParseException( Dict.get(Dict.ERROR_ANALYZE_POSTPROCESS) + e.getMessage() );
		}
		
		// TODO: delete
		System.out.println(keysByChannel);
		System.out.println(activityByChannel);
		System.out.println(noteOnOffByChannel);
		System.out.println(markerTicks);
		System.out.println(markers);
		System.out.println(noteHistory);
	}
	
	/**
	 * Calculates the channel activity for the given channel at the given tick.
	 * 
	 * @param channel  MIDI channel
	 * @param tick     tickstamp of the sequence
	 * @return   the channel activity
	 */
	public static boolean getChannelActivity( byte channel, long tick ) {
		
		// get ticks of this channel
		TreeMap<Long, Integer> ticksInChannel = activityByChannel.get( channel );
		if ( null == ticksInChannel )
			// channel not used at all
			return false;
		
		// get the last activity
		Entry<Long, Integer> activityState = ticksInChannel.floorEntry( tick );
		if ( null == activityState )
			// nothing happened in the channel so far
			return false;
		
		// inactive?
		if ( 0 == activityState.getValue() )
			return false;
		
		// active
		return true;
	}
	
	/**
	 * Calculates the note history for the given channel at the given tick.
	 * 
	 * The returned history consists of past and future notes, ordered by the tickstamp
	 * of their occurrence. Each entry contains the following parts:
	 * 
	 * - **index 0**: note number
	 * - **index 1**: volume (more correct: velocity)
	 * - **index 2**: tickstamp
	 * - **index 3**: past/future marker (**0** = presence or past, **1** = future)
	 * 
	 * @param channel  MIDI channel
	 * @param tick     tickstamp of the sequence
	 * @return   the note history
	 */
	public static ArrayList<Long[]> getNoteHistory( byte channel, long tick ) {
		
		ArrayList<Long[]> result = new ArrayList<Long[]>();
		TreeMap<Long, TreeMap<Byte, Byte>> channelHistory = noteHistory.get( channel );
		
		// get past notes
		long lastTick = tick;
		int i = 0;
		PAST:
		while ( i < NOTE_HISTORY_BUFFER_SIZE_PAST ) {
			
			// get all notes from the last tick
			Entry<Long, TreeMap<Byte, Byte>> notesAtTickEntry = channelHistory.floorEntry( lastTick );
			if ( null == notesAtTickEntry )
				break PAST;
			lastTick = notesAtTickEntry.getKey();
			TreeMap<Byte, Byte> notesAtTick = notesAtTickEntry.getValue();
			
			// each note at lastTick
			Set<Entry<Byte, Byte>> noteEntrySet = notesAtTick.entrySet();
			for ( Entry<Byte, Byte> noteEntry : noteEntrySet ) {
				byte note   = noteEntry.getKey();
				byte volume = noteEntry.getValue();
				
				Long[] row = {
					(long) note,    // note number
					(long) volume,  // velocity
					lastTick,       // tick
					0L,             // 0 = past; 1 = future
				};
				result.add( row );
				
				i++;
				if ( i >= NOTE_HISTORY_BUFFER_SIZE_PAST )
					break PAST;
			}
			
			// go further into the past
			lastTick--;
		}
		
		// reverse the order of the past notes
		Collections.reverse( result );
		
		// get future notes
		long nextTick = tick + 1;
		i = 0;
		FUTURE:
		while ( i < NOTE_HISTORY_BUFFER_SIZE_FUTURE ) {
			
			// get all notes from the next tick
			Entry<Long, TreeMap<Byte, Byte>> notesAtTickEntry = channelHistory.ceilingEntry( nextTick );
			if ( null == notesAtTickEntry )
				break FUTURE;
			nextTick = notesAtTickEntry.getKey();
			TreeMap<Byte, Byte> notesAtTick = notesAtTickEntry.getValue();
			
			// each note at nextTick
			Set<Entry<Byte, Byte>> noteEntrySet = notesAtTick.entrySet();
			for ( Entry<Byte, Byte> noteEntry : noteEntrySet ) {
				byte note   = noteEntry.getKey();
				byte volume = noteEntry.getValue();
				
				Long[] row = {
					(long) note,    // note number
					(long) volume,  // velocity
					nextTick,       // tick
					1L,             // 0 = past; 1 = future
				};
				result.add( row );
				
				i++;
				if ( i >= NOTE_HISTORY_BUFFER_SIZE_FUTURE )
					break FUTURE;
			}
			
			// go further into the future
			nextTick++;
		}
		
		return result;
	}
}