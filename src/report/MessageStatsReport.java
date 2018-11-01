/* 
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details. 
 */
package report;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import core.DTNHost;
import core.Message;
import core.MessageListener;

/**
 * Report for generating different kind of total statistics about message
 * relaying performance. Messages that were created during the warm up period
 * are ignored.
 * <P><strong>Note:</strong> if some statistics could not be created (e.g.
 * overhead ratio if no messages were delivered) "NaN" is reported for
 * double values and zero for integer median(s).
 */
public class MessageStatsReport extends Report implements MessageListener {
	private Map<String, Double> creationTimes;
	private List<Double> latencies1;
	private List<Double> latencies2;
	private int nrofRelayed1;
	private int nrofCreated1;
	private int nrofDelivered1;
	
	private int nrofRelayed2;
	private int nrofCreated2;
	private int nrofDelivered2;
	
	
	
	/**
	 * Constructor.
	 */
	public MessageStatsReport() {
		init();
	}

	@Override
	protected void init() {
		super.init();
		this.creationTimes = new HashMap<String, Double>();
		this.latencies1 = new ArrayList<Double>();
		this.latencies2 = new ArrayList<Double>();
		this.nrofRelayed1 = 0;
		this.nrofCreated1 = 0;
		this.nrofDelivered1 = 0;
		this.nrofRelayed2 = 0;
		this.nrofCreated2 = 0;
		this.nrofDelivered2 = 0;
	}

	
	public void messageDeleted(Message m, DTNHost where, boolean dropped) {
		if (isWarmupID(m.getId())) {
			return;
		}
	}

	
	public void messageTransferAborted(Message m, DTNHost from, DTNHost to) {
		if (isWarmupID(m.getId())) {
			return;
		}
	}

	
	public void messageTransferred(Message m, DTNHost from, DTNHost to,
			boolean finalTarget) {
		if (isWarmupID(m.getId())) {
			return;
		}
		
		String mid = m.getId();
		String prefix = mid.substring(0,1);
		String cStr = "C", iStr = "I";
		if(prefix.equals(cStr)){
			this.nrofRelayed1++;
		}
		else if(prefix.equals(iStr)){
			this.nrofRelayed2++;
		}
		
		if (finalTarget) {
			if(prefix.equals(cStr)){
				this.latencies1.add(getSimTime() - this.creationTimes.get(m.getId()) );
				this.nrofDelivered1++;
			}
			else if(prefix.equals(iStr)){
				this.latencies2.add(getSimTime() - this.creationTimes.get(m.getId()) );
				this.nrofDelivered2++;
			}
		}
	}


	public void newMessage(Message m) {
		if (isWarmup()) {
			addWarmupID(m.getId());
			return;
		}
		
		this.creationTimes.put(m.getId(), getSimTime());
		
		String mid = m.getId();
		String prefix = mid.substring(0,1);
		String cStr = "C", iStr = "I";
		if(prefix.equals(cStr)){
			this.nrofCreated1++;
		}
		else if(prefix.equals(iStr)){
			this.nrofCreated2++;
		}
		
		
	}
	
	
	public void messageTransferStarted(Message m, DTNHost from, DTNHost to) {
		if (isWarmupID(m.getId())) {
			return;
		}
	}
	

	@Override
	public void done() {
		double deliveryProb1 = 0; // delivery probability
		double overHead1 = Double.NaN;	// overhead ratio
		
		double deliveryProb2 = 0; // delivery probability
		double overHead2 = Double.NaN;	// overhead ratio
		
		if (this.nrofCreated1 > 0) {
			deliveryProb1 = (1.0 * this.nrofDelivered1) / this.nrofCreated2;
		}
		if (this.nrofDelivered1 > 0) {
			overHead1 = (1.0 * (this.nrofRelayed1 - this.nrofDelivered1)) /	this.nrofDelivered1;
		}
		
		if (this.nrofCreated2 > 0) {
			deliveryProb2 = (1.0 * this.nrofDelivered2) / this.nrofCreated2;
		}
		if (this.nrofDelivered2 > 0) {
			overHead2 = (1.0 * (this.nrofRelayed2 - this.nrofDelivered2)) / this.nrofDelivered2;
		}
		
		String statsText = "- Content report -\n"+ 
				nrofCreated1+"\n"+nrofRelayed1+"\n"+nrofCreated2+"\n"+nrofRelayed2+"\n"+nrofDelivered1+"\n"+format(deliveryProb1)+"\n"+format(overHead1)+"\n"+getAverage(this.latencies1)+"\n"
				;
		
		write(statsText);
		super.done();
	}
	
}
