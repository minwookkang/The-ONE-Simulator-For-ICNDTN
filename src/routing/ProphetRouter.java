/* 
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details. 
 */
package routing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import routing.util.RoutingInfo;
import util.Tuple;
import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimClock;

/**
 * Implementation of PRoPHET router as described in 
 * <I>Probabilistic routing in intermittently connected networks</I> by
 * Anders Lindgren et al.
 */
public class ProphetRouter extends ActiveRouter {
	/** delivery predictability initialization constant*/
	public static final double P_INIT = 0.75;
	/** delivery predictability transitivity scaling constant default value */
	public static final double DEFAULT_BETA = 0.25;
	/** delivery predictability aging constant */
	public static final double GAMMA = 0.98;
	
	/** Prophet router's setting namespace ({@value})*/ 
	public static final String PROPHET_NS = "ProphetRouter";
	/**
	 * Number of seconds in time unit -setting id ({@value}).
	 * How many seconds one time unit is when calculating aging of 
	 * delivery predictions. Should be tweaked for the scenario.*/
	public static final String SECONDS_IN_UNIT_S ="secondsInTimeUnit";
	
	/**
	 * Transitivity scaling constant (beta) -setting id ({@value}).
	 * Default value for setting is {@link #DEFAULT_BETA}.
	 */
	public static final String BETA_S = "beta";

	/** the value of nrof seconds in time unit -setting */
	private int secondsInTimeUnit;
	/** value of beta setting */
	private double beta;

	/** delivery predictabilities */
	private Map<DTNHost, Double> preds;
	private Map<String, Double> cpreds;
	
	/** last delivery predictability update (sim)time */
	private double lastAgeUpdate;
	
	public static final int nrofContents = 100;
	public static final int nrofInterests = 1000;
	public static final int inteval = 20;
	public static final int TTL = 1440;
	public static final int cTTL = 300;
	public static final int iTTL = 60;
	
	
	/**
	 * Constructor. Creates a new message router based on the settings in
	 * the given Settings object.
	 * @param s The settings object
	 */
	public ProphetRouter(Settings s) {
		super(s);
		Settings prophetSettings = new Settings(PROPHET_NS);
		secondsInTimeUnit = prophetSettings.getInt(SECONDS_IN_UNIT_S);
		if (prophetSettings.contains(BETA_S)) {
			beta = prophetSettings.getDouble(BETA_S);
		}
		else {
			beta = DEFAULT_BETA;
		}

		initPreds();
	}

	/**
	 * Copyconstructor.
	 * @param r The router prototype where setting values are copied from
	 */
	protected ProphetRouter(ProphetRouter r) {
		super(r);
		this.secondsInTimeUnit = r.secondsInTimeUnit;
		this.beta = r.beta;
		initPreds();
	}
	
	/**
	 * Initializes predictability hash
	 */
	private void initPreds() {
		
		this.preds = new HashMap<DTNHost, Double>();
		this.cpreds = new HashMap<String, Double>();
		
	}

	@Override
	public void changedConnection(Connection con) {
		super.changedConnection(con);
		
		if (con.isUp()) {
			DTNHost otherHost = con.getOtherNode(getHost());
			updateDeliveryPredFor(otherHost);
			updateTransitivePreds(otherHost);
			updateContentDeliveryPredFor(otherHost);
			updateMessageInformation(otherHost);
		}
	}
	
	/**
	 * Updates delivery predictions for a host.
	 * <CODE>P(a,b) = P(a,b)_old + (1 - P(a,b)_old) * P_INIT</CODE>
	 * @param host The host we just met
	 */
	private void updateDeliveryPredFor(DTNHost host) {
		double oldValue = getPredFor(host);
		double newValue = oldValue + (1 - oldValue) * P_INIT;
		preds.put(host, newValue);
	}
	
	private void updateContentDeliveryPredFor(DTNHost host) {
		Collection<Message> ms = host.getMessageCollection();
		
		for (Message m : ms) {
			String mid = m.getId();
			String prefix = mid.substring(0,1);
			String cStr = "C";
			
			if(prefix.equals(cStr)){
				double oldValue = getContentPredFor(mid);
				double newValue = oldValue + (1 - oldValue) * P_INIT;
				cpreds.put(mid, newValue);
			}
		}		
	}
	
	private void updateMessageInformation(DTNHost host) {
		Collection<Message> ms = host.getMessageCollection();
		
		for (Message m : ms) {
			String mid = m.getId();
			String prefix = mid.substring(0,1);
			String cStr = "C", iStr = "I";
			
			String cid = m.getContentID();
			
			if(prefix.equals(cStr)){
				if(this.contents.containsKey(cid)){ // 상대 content, 나의 content가 동일 content name인 경우 -> 나의 content의 requester 정보 merging
					for(DTNHost req : m.getRequesters()){
						if(!this.messages.get(mid).getRequesters().contains(req)){
							this.messages.get(mid).setRequester(req);
						}
					}
				}
				/*
				if(this.interests.containsKey(cid)){ // 상대 content, 나의 interest가 동일 content name인 경우 -> 나의 interest 삭제
					this.messages.get(cid).setTtl(1);
				}
				*/				
			}
			else if(prefix.equals(iStr)){
				if(this.contents.containsKey(cid)){ // 상대 interest, 나의 content가 동일 content name인 경우 -> 나의 content requester 정보 merging
					for(DTNHost req : m.getRequesters()){
						if(!this.messages.get(cid).getRequesters().contains(req)){
							this.messages.get(cid).setRequester(req);
						}
					}
				}
				if(this.interests.containsKey(cid)){ // 상대 interest, 나의 interest가 동일 content name인 경우 -> 나의 interest requester 정보 merging
					String iid = this.interests.get(cid).getId();
					for(DTNHost req : m.getRequesters()){
						if(!this.messages.get(iid).getRequesters().contains(req)){
							this.messages.get(iid).setRequester(req);
						}
					}
				}
			}
		}		
	}
	
	/**
	 * Returns the current prediction (P) value for a host or 0 if entry for
	 * the host doesn't exist.
	 * @param host The host to look the P for
	 * @return the current P value
	 */
	public double getPredFor(DTNHost host) {
		ageDeliveryPreds(); // make sure preds are updated before getting
		if (preds.containsKey(host)) {
			return preds.get(host);
		}
		else {
			return 0;
		}
	}
	
	public double getContentPredFor(String cid) {
		if (cpreds.containsKey(cid)) {
			return cpreds.get(cid);
		}
		else {
			return 0;
		}
	}
	
	/**
	 * Updates transitive (A->B->C) delivery predictions.
	 * <CODE>P(a,c) = P(a,c)_old + (1 - P(a,c)_old) * P(a,b) * P(b,c) * BETA
	 * </CODE>
	 * @param host The B host who we just met
	 */
	private void updateTransitivePreds(DTNHost host) {
		MessageRouter otherRouter = host.getRouter();
		assert otherRouter instanceof ProphetRouter : "PRoPHET only works " + 
			" with other routers of same type";
		
		double pForHost = getPredFor(host); // P(a,b)
		Map<DTNHost, Double> othersPreds = 
			((ProphetRouter)otherRouter).getDeliveryPreds();
		
		for (Map.Entry<DTNHost, Double> e : othersPreds.entrySet()) {
			if (e.getKey() == getHost()) {
				continue; // don't add yourself
			}
			
			double pOld = getPredFor(e.getKey()); // P(a,c)_old
			double pNew = pOld + ( 1 - pOld) * pForHost * e.getValue() * beta;
			preds.put(e.getKey(), pNew);
		}
	}

	/**
	 * Ages all entries in the delivery predictions.
	 * <CODE>P(a,b) = P(a,b)_old * (GAMMA ^ k)</CODE>, where k is number of
	 * time units that have elapsed since the last time the metric was aged.
	 * @see #SECONDS_IN_UNIT_S
	 */
	private void ageDeliveryPreds() {
		double timeDiff = (SimClock.getTime() - this.lastAgeUpdate) / 
			secondsInTimeUnit;
		
		if (timeDiff == 0) {
			return;
		}
		
		double mult = Math.pow(GAMMA, timeDiff);
		for (Map.Entry<DTNHost, Double> e : preds.entrySet()) {
			e.setValue(e.getValue()*mult);
		}
		
		this.lastAgeUpdate = SimClock.getTime();
	}
	
	/**
	 * Returns a map of this router's delivery predictions
	 * @return a map of this router's delivery predictions
	 */
	private Map<DTNHost, Double> getDeliveryPreds() {
		ageDeliveryPreds(); // make sure the aging is done
		return this.preds;
	}
	
	@Override 
	public boolean createNewMessage(Message m) {
		makeRoomForNewMessage(m.getSize());
		
		String mid = m.getId();
		String prefix = mid.substring(0,1);
		int suffix = Integer.parseInt(mid.substring(1, mid.length()));
		String cStr = "C", iStr = "I";	
		if(prefix.equals(cStr) && suffix <= nrofContents){
			m.setContentID(mid);
			m.setTtl(TTL);
			this.cpreds.put(mid, 1.0);
			
			addToMessages(m, true);			
			return true;
			
		}
		else if(prefix.equals(iStr) && suffix <= nrofInterests){
			Random rd = new Random();
			String cid = null;
			while(true){
				Integer n = rd.nextInt(nrofContents) + 1;
				//Integer n = rd.nextInt(1439) + 1;
				cid = cStr + n;
				if(!this.hasMessage(cid)){
					break;
				}
			}			
			m.setContentID(cid);
			m.setTtl(TTL);
			m.setRequester(this.getHost());				
			addToMessages(m, true);
			
			return true;
		}
		return false;
	}
	
	
	@Override
	public Message messageTransferred(String id, DTNHost from) {
		Message m = super.messageTransferred(id, from);
		
		String mid = m.getId();
		String prefix = mid.substring(0,1);
		String cStr = "C", iStr = "I";
		
		if(prefix.equals(cStr)){
			m.setTtl(cTTL);
			if(m.getRequesters().contains(this.getHost())){
				if(m.getRequesters().size() > 1){ // 아직 content의 requester가 남은 경우
					m.removeRequester(this.getHost());
				}
				else{ // content의 requester가 하나인 경우
					m.setTtl(1);
				}
			}
		}
		else if(prefix.equals(iStr)){
			m.setTtl(iTTL);
		}
		
		return m;
	}
	
	@Override
	public void update() {
		super.update();
		if (!canStartTransfer() ||isTransferring()) {
			return; // nothing to transfer or is currently transferring 
		}
		
		// try messages that could be delivered to final recipient
		if (exchangeDeliverableMessages() != null) {
			return;
		}
		
		tryOtherMessages();		
	}
	
	/**
	 * Tries to send all other messages to all connected hosts ordered by
	 * their delivery probability
	 * @return The return value of {@link #tryMessagesForConnected(List)}
	 */
	private Tuple<Message, Connection> tryOtherMessages() {
			
		Collection<Message> msgCollection = getMessageCollection();
		
		/* for all connected hosts collect all messages that have a higher
		   probability of delivery by the other host */
		for (Connection con : getConnections()) {
			DTNHost other = con.getOtherNode(getHost());
			ProphetRouter othRouter = (ProphetRouter)other.getRouter();
			
			if (othRouter.isTransferring()) {
				continue; // skip hosts that are transferring
			}
			
			for (Message m : msgCollection) {
				String mid = m.getId();
				String prefix = mid.substring(0,1);
				String cStr = "C", iStr = "I";
				String cid = m.getContentID();
				
				if(m.getRequesters().size() < 1){
					continue;
				}
				
				if(prefix.equals(cStr)){					
					if (othRouter.contents.containsKey(mid)) {
						continue; // skip messages that the other one has
					}
					else{
						List<DTNHost> reqs = new ArrayList<DTNHost>(m.getRequesters());				
						/*
						// 1. and
						int cnt = reqs.size();
						for(DTNHost req : reqs){
							if(othRouter.getPredFor(req) >= getPredFor(req)){
								cnt--;
							}
						}
						if(cnt == 0 || m.getRequesters().contains(other)){
							startTransfer(m, con);
						}
						*/
						
						// 2. or
						int cnt2 = 0;
						for(DTNHost req : reqs){
							if(othRouter.getPredFor(req) >= getPredFor(req)){
								cnt2++;
							}
						}
						if(cnt2 > 0 || m.getRequesters().contains(other)){
							startTransfer(m, con);
						}
						
						/*
						// 3. average
						double p1 = 0, p2 = 0;
						int cnt1 = 0, cnt2 = 0;
						//System.out.println(m.getRequesters().size());
						for(DTNHost req : reqs){
							p1 += othRouter.getPredFor(req);
							p2 += getPredFor(req);
							//System.out.println(othRouter.getPredFor(req)+", "+getPredFor(req));
							if(othRouter.getPredFor(req) > 0){
								cnt1++;
							}
							if(getPredFor(req) > 0){
								cnt2++;
							}
						}
						p1 = p1/cnt1;
						p2 = p2/cnt2;
						//System.out.println(p1+", "+p2);
						if(p1 >= p2 || m.getRequesters().contains(other)){
							//System.out.println(p1+", "+p2);
							startTransfer(m, con);
						}
						*/
					}					
				}
				else if(prefix.equals(iStr)){					
					if(othRouter.interests.containsKey(cid) || othRouter.contents.containsKey(cid)){
						continue;
					}
					else{
						if(othRouter.getContentPredFor(cid) > getContentPredFor(cid)){
							startTransfer(m, con);
						}
					}					
				}
			}			
		}
		
		// sort the message-connection tuples
		//Collections.sort(messages, new TupleComparator());
		return null;	// try to send messages
	}
	
	/**
	 * Comparator for Message-Connection-Tuples that orders the tuples by
	 * their delivery probability by the host on the other side of the 
	 * connection (GRTRMax)
	 */
	private class TupleComparator implements Comparator 
		<Tuple<Message, Connection>> {

		public int compare(Tuple<Message, Connection> tuple1,
				Tuple<Message, Connection> tuple2) {
			// delivery probability of tuple1's message with tuple1's connection
			double p1 = ((ProphetRouter)tuple1.getValue().
					getOtherNode(getHost()).getRouter()).getPredFor(
					tuple1.getKey().getTo());
			// -"- tuple2...
			double p2 = ((ProphetRouter)tuple2.getValue().
					getOtherNode(getHost()).getRouter()).getPredFor(
					tuple2.getKey().getTo());

			// bigger probability should come first
			if (p2-p1 == 0) {
				/* equal probabilities -> let queue mode decide */
				return compareByQueueMode(tuple1.getKey(), tuple2.getKey());
			}
			else if (p2-p1 < 0) {
				return -1;
			}
			else {
				return 1;
			}
		}
	}
	
	@Override
	public RoutingInfo getRoutingInfo() {
		ageDeliveryPreds();
		RoutingInfo top = super.getRoutingInfo();
		RoutingInfo ri = new RoutingInfo(preds.size() + 
				" delivery prediction(s)");
		
		for (Map.Entry<DTNHost, Double> e : preds.entrySet()) {
			DTNHost host = e.getKey();
			Double value = e.getValue();
			
			ri.addMoreInfo(new RoutingInfo(String.format("%s : %.6f", 
					host, value)));
		}
		
		top.addMoreInfo(ri);
		return top;
	}
	
	@Override
	public MessageRouter replicate() {
		ProphetRouter r = new ProphetRouter(this);
		return r;
	}

}
