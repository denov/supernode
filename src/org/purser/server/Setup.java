package org.purser.server;

import java.io.BufferedReader;
import java.io.Console;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Setup {
    private static final Logger log = LoggerFactory.getLogger(Setup.class);

	static public void setup () throws IOException
	{
		  String password = null;
		  File master = new File ("MASTERPASSWORD");
		  if ( master.exists() )
		  {
			  FileReader reader = new FileReader(master);
			  password = new BufferedReader(reader).readLine();
			  reader.close();
		  }
		  if ( password == null )
		  {
			  Console console = System.console();
			    if (console == null) {
			      throw new IOException ("unable to obtain console");
			    }

			    password = new String(console.readPassword("MASTERPASSWORD: "));
		  }
		  System.setProperty("javax.net.ssl.keyStorePassword", password);
		  System.setProperty("javax.net.ssl.trustStorePassword", password);
	}
	
}
