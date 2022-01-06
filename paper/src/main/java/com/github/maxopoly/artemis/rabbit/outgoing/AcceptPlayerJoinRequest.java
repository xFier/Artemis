package com.github.maxopoly.artemis.rabbit.outgoing;

import org.json.JSONObject;

import com.github.maxopoly.zeus.rabbit.RabbitMessage;
import com.github.maxopoly.zeus.rabbit.incoming.artemis.AcceptPlayerJoin;

public class AcceptPlayerJoinRequest extends RabbitMessage {

	public AcceptPlayerJoinRequest(String transactionID) {
		super(transactionID);
	}

	@Override
	protected void enrichJson(JSONObject json) {
		//no content
	}

	@Override
	public String getIdentifier() {
		return AcceptPlayerJoin.ID;
	}

}
