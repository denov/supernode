/*
 * Copyright 2012 Tamas Blummer tamas@bitsofproof.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.bitsofproof.supernode.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import com.bitsofproof.supernode.messages.BitcoinMessageListener;
import com.bitsofproof.supernode.messages.GetDataMessage;
import com.bitsofproof.supernode.messages.InvMessage;
import com.bitsofproof.supernode.messages.TxMessage;
import com.bitsofproof.supernode.model.Blk;
import com.bitsofproof.supernode.model.BlockStore;
import com.bitsofproof.supernode.model.Owner;
import com.bitsofproof.supernode.model.Tx;
import com.bitsofproof.supernode.model.TxIn;
import com.bitsofproof.supernode.model.TxOut;

public class TxHandler implements ChainListener
{
	private static final Logger log = LoggerFactory.getLogger (TxHandler.class);

	private PlatformTransactionManager transactionManager;

	private final Map<String, Tx> unconfirmed = new HashMap<String, Tx> ();
	private final Map<String, ArrayList<TxOut>> spentByAddress = new HashMap<String, ArrayList<TxOut>> ();
	private final Map<String, ArrayList<TxOut>> receivedByAddress = new HashMap<String, ArrayList<TxOut>> ();

	public TxHandler (final BitcoinNetwork network, final ChainLoader loader)
	{
		final BlockStore store = network.getStore ();
		loader.addChainListener (this);

		network.addListener ("inv", new BitcoinMessageListener<InvMessage> ()
		{
			@Override
			public void process (InvMessage im, BitcoinPeer peer)
			{
				GetDataMessage get = (GetDataMessage) peer.createMessage ("getdata");
				for ( byte[] h : im.getTransactionHashes () )
				{
					String hash = new Hash (h).toString ();
					synchronized ( unconfirmed )
					{
						if ( unconfirmed.get (hash) == null )
						{
							log.trace ("heard about new transaction " + hash + " from " + peer.getAddress ());
							get.getTransactions ().add (h);
						}
					}
				}
				if ( !loader.isBehind () && get.getTransactions ().size () > 0 )
				{
					log.trace ("asking for transaction details from " + peer.getAddress ());
					peer.send (get);
				}
			}
		});
		network.addListener ("tx", new BitcoinMessageListener<TxMessage> ()
		{

			@Override
			public void process (final TxMessage txm, final BitcoinPeer peer)
			{
				log.trace ("received transaction details for " + txm.getTx ().getHash () + " from " + peer.getAddress ());
				if ( !unconfirmed.containsKey (txm.getTx ().getHash ()) )
				{
					if ( new TransactionTemplate (transactionManager).execute (new TransactionCallback<Boolean> ()
					{
						@Override
						public Boolean doInTransaction (TransactionStatus status)
						{
							status.setRollbackOnly ();
							try
							{
								store.validateTransaction (txm.getTx ());
								log.trace ("Caching unconfirmed transaction " + txm.getTx ().getHash ());
								synchronized ( unconfirmed )
								{
									unconfirmed.put (txm.getTx ().getHash (), txm.getTx ());
									for ( TxIn in : txm.getTx ().getInputs () )
									{
										for ( Owner o : in.getSource ().getOwners () )
										{
											ArrayList<TxOut> spent = spentByAddress.get (o.getAddress ());
											if ( spent == null )
											{
												spent = new ArrayList<TxOut> ();
												spentByAddress.put (o.getAddress (), spent);
											}
											spent.add (in.getSource ());
										}
									}
									for ( TxOut out : txm.getTx ().getOutputs () )
									{
										for ( Owner o : out.getOwners () )
										{
											ArrayList<TxOut> spent = receivedByAddress.get (o.getAddress ());
											if ( spent == null )
											{
												spent = new ArrayList<TxOut> ();
												receivedByAddress.put (o.getAddress (), spent);
											}
											spent.add (out);
										}
									}
								}
								return true;
							}
							catch ( ValidationException e )
							{
								log.trace ("Rejeting transaction " + txm.getTx ().getHash () + " from " + peer.getAddress (), e);
							}
							return false;
						}
					}).booleanValue () )
					{
						for ( BitcoinPeer p : network.getConnectPeers () )
						{
							if ( p != peer )
							{
								TxMessage tm = (TxMessage) p.createMessage ("tx");
								tm.setTx (txm.getTx ());
								p.send (tm);
							}
						}
					}
				}
			}
		});
	}

	public List<TxOut> getSpentByAddress (List<String> addresses)
	{
		List<TxOut> spent = new ArrayList<TxOut> ();
		for ( String a : addresses )
		{
			List<TxOut> s = spentByAddress.get (a);
			if ( s != null )
			{
				spent.addAll (s);
			}
		}
		return spent;
	}

	public List<TxOut> getReceivedByAddress (List<String> addresses)
	{
		List<TxOut> received = new ArrayList<TxOut> ();
		for ( String a : addresses )
		{
			List<TxOut> s = receivedByAddress.get (a);
			if ( s != null )
			{
				received.addAll (s);
			}
		}
		return received;
	}

	public Tx getTransaction (String hash)
	{
		return unconfirmed.get (hash);
	}

	public void setTransactionManager (PlatformTransactionManager transactionManager)
	{
		this.transactionManager = transactionManager;
	}

	@Override
	public void blockAdded (final Blk blk)
	{
		new TransactionTemplate (transactionManager).execute (new TransactionCallbackWithoutResult ()
		{

			@Override
			protected void doInTransactionWithoutResult (TransactionStatus status)
			{
				synchronized ( unconfirmed )
				{
					if ( unconfirmed.isEmpty () )
					{
						return;
					}
					for ( Tx tx : blk.getTransactions () )
					{
						unconfirmed.remove (tx.getHash ());
						for ( TxIn in : tx.getInputs () )
						{
							for ( Owner o : in.getSource ().getOwners () )
							{
								List<TxOut> s = spentByAddress.get (o.getAddress ());
								if ( s != null )
								{
									s.remove (in.getSource ());
								}
							}
						}
						for ( TxOut out : tx.getOutputs () )
						{
							for ( Owner o : out.getOwners () )
							{
								List<TxOut> s = receivedByAddress.get (o.getAddress ());
								if ( s != null )
								{
									s.remove (out);
								}
							}
						}
					}
				}
			}
		});
	}
}
