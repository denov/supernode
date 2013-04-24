/*
 * Copyright 2013 bits of proof zrt.
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
package com.bitsofproof.supernode.api;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsofproof.supernode.api.BloomFilter.UpdateMode;
import com.bitsofproof.supernode.api.Transaction.TransactionSink;
import com.bitsofproof.supernode.api.Transaction.TransactionSource;

class DefaultAccountManager implements TransactionListener, TrunkListener, AccountManager
{
	private static final Logger log = LoggerFactory.getLogger (DefaultAccountManager.class);

	private static final long DUST = 10000;

	private BCSAPI api;

	private final LocalUTXO utxo = new LocalUTXO ();

	private final HashMap<String, Long> colorBalances = new HashMap<String, Long> ();

	private long balance = 0;

	private final List<AccountListener> accountListener = Collections.synchronizedList (new ArrayList<AccountListener> ());

	private final Map<String, Transaction> received = new HashMap<String, Transaction> ();

	private Account account;
	private Wallet wallet;

	public void setApi (BCSAPI api)
	{
		this.api = api;
	}

	public void setWallet (Wallet wallet)
	{
		this.wallet = wallet;
	}

	public void setAccount (Account account) throws BCSAPIException
	{
		this.account = account;

		for ( Transaction t : wallet.getTransactions () )
		{
			updateWithTransaction (t);
		}
		Collection<byte[]> addresses = account.getAddresses ();
		BloomFilter filter = BloomFilter.createOptimalFilter (Math.max (addresses.size (), 100), 1.0 / 1000000.0, UpdateMode.all);
		for ( byte[] a : addresses )
		{
			filter.add (a);
		}
		api.registerTrunkListener (this);
		api.registerFilteredListener (filter, this);
		api.scanTransactions (addresses, UpdateMode.all, wallet.getTimeStamp (), this);
	}

	private boolean updateWithTransaction (Transaction t)
	{
		synchronized ( utxo )
		{
			boolean modified = false;
			if ( !received.containsKey (t.getHash ()) )
			{
				received.put (t.getHash (), t);
				for ( TransactionInput in : t.getInputs () )
				{
					TransactionOutput o = utxo.get (in.getSourceHash (), in.getIx ());
					if ( o != null )
					{
						modified = true;
						balance -= o.getValue ();
						utxo.remove (in.getSourceHash (), in.getIx ());
						if ( o.getColor () != null )
						{
							Long balance = colorBalances.get (o.getColor ());
							colorBalances.put (o.getColor (), balance.longValue () - o.getValue ());
						}
					}
				}
				int ix = 0;
				for ( TransactionOutput o : t.getOutputs () )
				{
					byte[] address = o.getOutputAddress ();
					if ( address != null && account.getKeyForAddress (address) != null )
					{
						modified = true;
						balance += o.getValue ();
						utxo.add (t.getHash (), ix, o);
						if ( o.getColor () != null )
						{
							Long balance = colorBalances.get (o.getColor ());
							if ( balance != null )
							{
								colorBalances.put (o.getColor (), balance.longValue () + o.getValue ());
							}
							else
							{
								colorBalances.put (o.getColor (), o.getValue ());
							}
						}
					}
					++ix;
				}
			}
			if ( modified )
			{
				log.trace ("Updated account " + account.getName () + " with " + t.getHash () + " balance " + balance);
			}

			return modified;
		}
	}

	@Override
	public void trunkUpdate (List<Block> removed, List<Block> added)
	{
		if ( added != null )
		{
			boolean modified = false;
			for ( Block b : added )
			{
				for ( Transaction t : b.getTransactions () )
				{
					modified |= updateWithTransaction (t);
				}
			}
			if ( modified )
			{
				notifyListener ();
			}
		}
	}

	@Override
	public void process (Transaction t)
	{
		if ( updateWithTransaction (t) )
		{
			notifyListener ();
		}
	}

	@Override
	public Transaction pay (byte[] receiver, long amount, long fee) throws ValidationException, BCSAPIException
	{
		synchronized ( utxo )
		{
			List<TransactionSource> sources = utxo.getSufficientSources (amount, fee, null);
			if ( sources == null )
			{
				throw new ValidationException ("Insufficient funds to pay " + (amount + fee));
			}
			List<TransactionSink> sinks = new ArrayList<TransactionSink> ();

			long in = 0;
			for ( TransactionSource o : sources )
			{
				in += o.getOutput ().getValue ();
			}
			TransactionSink target = new TransactionSink (receiver, amount);
			if ( (in - amount - fee) > 0 )
			{
				TransactionSink change = new TransactionSink (account.getNextKey ().getAddress (), in - amount - fee);
				if ( new SecureRandom ().nextBoolean () )
				{
					sinks.add (target);
					sinks.add (change);
				}
				else
				{
					sinks.add (change);
					sinks.add (target);
				}
			}
			else
			{
				sinks.add (target);
			}
			return Transaction.createSpend (account, sources, sinks, fee);
		}
	}

	@Override
	public Transaction split (long[] amounts, long fee) throws ValidationException, BCSAPIException
	{
		synchronized ( utxo )
		{
			List<TransactionSink> sinks = new ArrayList<TransactionSink> ();

			long amount = 0;
			for ( long a : amounts )
			{
				amount += a;
			}
			List<TransactionSource> sources = utxo.getSufficientSources (amount, fee, null);
			if ( sources == null )
			{
				throw new ValidationException ("Insufficient funds to pay " + (amount + fee));
			}
			long in = 0;
			for ( TransactionSource o : sources )
			{
				in += o.getOutput ().getValue ();
			}
			for ( long a : amounts )
			{
				TransactionSink target = new TransactionSink (account.getNextKey ().getAddress (), a);
				sinks.add (target);
			}
			if ( (in - amount - fee) > 0 )
			{
				TransactionSink change = new TransactionSink (account.getNextKey ().getAddress (), in - amount - fee);
				sinks.add (change);
			}
			Collections.shuffle (sinks);
			return Transaction.createSpend (account, sources, sinks, fee);
		}
	}

	@Override
	public Transaction transfer (byte[] receiver, long units, long fee, Color color) throws ValidationException, BCSAPIException
	{
		synchronized ( utxo )
		{
			List<TransactionSink> sinks = new ArrayList<TransactionSink> ();

			long amount = units * color.getUnit ();
			List<TransactionSource> sources = utxo.getSufficientSources (amount, fee, color.getTransaction ());
			if ( sources == null )
			{
				throw new ValidationException ("Insufficient holdings to transfer " + units + " of " + color.getTransaction ());
			}
			long in = 0;
			long colorIn = 0;
			for ( TransactionSource o : sources )
			{
				in += o.getOutput ().getValue ();
				if ( color != null && o.getOutput ().getColor ().equals (color) )
				{
					colorIn += o.getOutput ().getValue ();
				}
			}
			TransactionSink target = new TransactionSink (receiver, amount);
			if ( colorIn > amount )
			{
				TransactionSink colorChange = new TransactionSink (account.getNextKey ().getAddress (), colorIn - amount);
				if ( new SecureRandom ().nextBoolean () )
				{
					sinks.add (target);
					sinks.add (colorChange);
				}
				else
				{
					sinks.add (colorChange);
					sinks.add (target);
				}
			}
			else
			{
				sinks.add (target);
			}
			sinks.add (new TransactionSink (account.getNextKey ().getAddress (), in - amount - fee));
			return Transaction.createSpend (account, sources, sinks, fee);
		}
	}

	@Override
	public Transaction createColorGenesis (long quantity, long unitSize, long fee) throws ValidationException, BCSAPIException
	{
		synchronized ( utxo )
		{
			List<TransactionSink> sinks = new ArrayList<TransactionSink> ();

			List<TransactionSource> sources = utxo.getSufficientSources (quantity * unitSize, fee, null);
			if ( sources == null )
			{
				throw new ValidationException ("Insufficient funds to issue color ");
			}
			long in = 0;
			for ( TransactionSource o : sources )
			{
				in += o.getOutput ().getValue ();
			}
			Key issuerKey = account.getNextKey ();
			TransactionSink target = new TransactionSink (issuerKey.getAddress (), quantity * unitSize);
			TransactionSink change = new TransactionSink (account.getNextKey ().getAddress (), in - quantity * unitSize - fee);
			sinks.add (target);
			sinks.add (change);
			return Transaction.createSpend (account, sources, sinks, fee);
		}
	}

	@Override
	public Transaction cashIn (ECKeyPair key, long fee) throws ValidationException, BCSAPIException
	{
		synchronized ( utxo )
		{
			// KeyFormatter formatter = new KeyFormatter (passpharse, wallet.getAddressFlag ());
			// Key key = formatter.parseSerializedKey (serialized);
			// String inAddress = AddressConverter.toSatoshiStyle (key.getAddress (), wallet.getAddressFlag ());
			// List<TransactionOutput> available = utxo.getAddressSources (inAddress);
			// if ( available.size () > 0 )
			// {
			// long sum = 0;
			// List<TransactionSource> sources = new ArrayList<TransactionSource> ();
			// for ( TransactionOutput o : available )
			// {
			// sources.add (new TransactionSource (o, key));
			// sum += o.getValue ();
			// }
			// List<TransactionSink> sinks = new ArrayList<TransactionSink> ();
			// // make it look like a spend
			// long a = Math.max ((((sum - fee) - Math.abs ((new SecureRandom ().nextLong () % (sum - fee)))) / DUST) * DUST, DUST);
			// long b = (sum - fee) - a;
			// sinks.add (new TransactionSink (wallet.getRandomKey ().getAddress (), a));
			// sinks.add (new TransactionSink (wallet.getRandomKey ().getAddress (), b));
			// return Transaction.createSpend (sources, sinks, fee);
			// }
			throw new ValidationException ("No input available on this key");
		}
	}

	@Override
	public long getBalance ()
	{
		synchronized ( utxo )
		{
			return balance;
		}
	}

	@Override
	public long getBalance (Color color)
	{
		synchronized ( utxo )
		{
			Long balance = colorBalances.get (color.getFungibleName ());
			if ( balance != null )
			{
				return balance.longValue () / color.getUnit ();
			}
		}
		return 0;
	}

	@Override
	public List<String> getColors ()
	{
		List<String> cols = new ArrayList<String> ();
		cols.addAll (colorBalances.keySet ());
		return cols;
	}

	@Override
	public void addAccountListener (AccountListener listener)
	{
		accountListener.add (listener);
	}

	@Override
	public void removeAccountListener (AccountListener listener)
	{
		accountListener.remove (listener);
	}

	private void notifyListener ()
	{
		synchronized ( accountListener )
		{
			for ( AccountListener l : accountListener )
			{
				try
				{
					l.accountChanged (this);
				}
				catch ( Exception e )
				{
				}
			}
		}
	}
}
