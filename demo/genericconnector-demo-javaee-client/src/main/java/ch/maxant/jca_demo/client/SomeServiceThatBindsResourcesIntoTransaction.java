/*
   Copyright 2015 Ant Kutschera

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */
package ch.maxant.jca_demo.client;

import static ch.maxant.jca_demo.client.IntegrationLayer.getAcquirer;
import static ch.maxant.jca_demo.client.IntegrationLayer.getBookingsystem;
import static ch.maxant.jca_demo.client.IntegrationLayer.getLetterwriter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Resource;
import javax.ejb.SessionContext;
import javax.ejb.Stateless;
import javax.sql.DataSource;

import ch.maxant.generic_jca_adapter.TransactionAssistanceFactory;
import ch.maxant.generic_jca_adapter.TransactionAssistant;
import ch.maxant.jca_demo.acquirer.Acquirer;
import ch.maxant.jca_demo.bookingsystem.BookingSystem;
import ch.maxant.jca_demo.letterwriter.LetterWriter;

/*
if necessary, here is an H2 XA config, which uses the same driver as the normal example H2 connection:

                <xa-datasource jndi-name="java:jboss/datasources/ExampleXADS" pool-name="ExampleXADS">
                   <driver>h2</driver>
                   <xa-datasource-property name="URL">jdbc:h2:tcp://localhost/~/test</xa-datasource-property>
                   <xa-pool>
                        <min-pool-size>10</min-pool-size>
                        <max-pool-size>20</max-pool-size>
                        <prefill>true</prefill>
                   </xa-pool>
                   <security>
                        <user-name>sa</user-name>
                        <password></password>
                   </security>
                </xa-datasource>
 */
@Stateless
public class SomeServiceThatBindsResourcesIntoTransaction {

    private final Logger log = Logger.getLogger(this.getClass().getName());

    @Resource(lookup = "java:/maxant/Acquirer")
    private TransactionAssistanceFactory acquirerFactory;

    @Resource(lookup = "java:/maxant/BookingSystem")
    private TransactionAssistanceFactory bookingFactory;

    @Resource(lookup = "java:/maxant/LetterWriter")
    private TransactionAssistanceFactory letterWriterFactory;
    
    @Resource(lookup = "java:/jdbc/MyXaDS")
//    @Resource(lookup = "java:jboss/datasources/ExampleXADS")
    private DataSource ds;

    @Resource
    private SessionContext ctx;

    public String doSomethingInvolvingSeveralResources(String refNumber)
            throws Exception {

        Acquirer acquirer = getAcquirer();
        BookingSystem bookingSystem = getBookingsystem();
        LetterWriter letterWriter = getLetterwriter();

        try (TransactionAssistant acquirerTransactionAssistant = acquirerFactory.getTransactionAssistant();
             TransactionAssistant bookingTransactionAssistant = bookingFactory.getTransactionAssistant();
             TransactionAssistant letterWriterTransactionAssistant = letterWriterFactory.getTransactionAssistant() 
        ) {
            int id = insertFirstRecordIntoDatabase();

            String acquirerResponse = acquirerTransactionAssistant
                    .executeInActiveTransaction(txid -> {
                        return acquirer.reserveMoney(txid, refNumber);
                    });
            log.log(Level.INFO, "reserved money...");

            String bookingResponse = bookingTransactionAssistant.executeInActiveTransaction(txid -> {
                return bookingSystem.reserveTickets(txid, refNumber);
            });
            log.log(Level.INFO, "reserved ticket...");
            
            String letterResponse = letterWriterTransactionAssistant.executeInActiveTransaction(txid -> {
                return letterWriter.writeLetter(txid, refNumber);
            });
            log.log(Level.INFO, "created workflow to send letter...");
            
            insertAnotherRecordIntoDatabase(refNumber, id);

            String response = acquirerResponse + "/" + bookingResponse + "/" + letterResponse;
            log.log(Level.INFO, "returning " + response);
            return response;
        } catch (SQLException e) {
            // have to catch SQLException explicitly since
            // its considered to be an application exception
            // since it inherits from Exception and not
            // RuntimeException - kinda sucks really.
            ctx.setRollbackOnly();
            throw e;
        }
    }

    private void insertAnotherRecordIntoDatabase(String refNum, int id) throws SQLException {
        try (Connection database = ds.getConnection();
            PreparedStatement statement = database.prepareStatement("insert into temp.address values (null,?,?)")) {
            statement.setString(2, "THIRD_" + new SimpleDateFormat("yyyy/MM/dd_HH:mm:ss.SSS").format(new Date()));
            if ("FAILDB".equals(refNum)) {
                statement.setInt(1, 0); // fails with FK Constraint exception
            } else {
                statement.setInt(1, id);
            }
            int cnt = statement.executeUpdate();
            log.log(Level.INFO, "wrote to db... " + cnt);
        }
    }

    private int insertFirstRecordIntoDatabase() throws SQLException {
        int id;
        try (Connection database = ds.getConnection();
            PreparedStatement statement = database.prepareStatement(
                "insert into temp.person values (null,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, "FIRST_" + new SimpleDateFormat("yyyy/MM/dd_HH:mm:ss.SSS").format(new Date()));
            int cnt = statement.executeUpdate();
            ResultSet generatedKeys = statement.getGeneratedKeys();
            generatedKeys.next();
            id = generatedKeys.getInt(1);
            log.log(Level.INFO, "wrote to db... " + cnt + " with ID " + id);
        }
        return id;
    }

}
