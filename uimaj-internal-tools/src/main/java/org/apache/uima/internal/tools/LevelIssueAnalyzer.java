/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.uima.internal.tools;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * The LevelIssueAnalyzer detects the Jira issues keys that are used in the commit comments since a
 * specified tag comment. So if a new level/tag is created the tool can detect all Jira issues that
 * are fixed since the last level/tag based on the tag comment. The level/tag comments must specify
 * the levelname as for example "levelname:uimaj-<version>-<buildNumber>"
 * (levelname:uimaj-2.1.0-003). For each detected Jira issue, the abstract of the issue is retrieved
 * from the web. The Jira issues that are part of the newly created level are written to a text file
 * called levelIssues.txt
 * 
 * Usage: LevelIssueAnalyzer <last level name>
 * 
 * Sample output: https://issues.apache.org/jira/browse/UIMA-257: [#UIMA-257] Document Analyzer
 * sometimes names style map file incorrectly - ASF JIRA
 * https://issues.apache.org/jira/browse/UIMA-256: [#UIMA-256] CVD manual not displayed in
 * distribution - ASF JIRA https://issues.apache.org/jira/browse/UIMA-266: [#UIMA-266]
 * DocumentAnalyzer also use wrong default directory docs/examples/data - ASF JIRA
 * 
 * 
 */
public class LevelIssueAnalyzer {

  private static final String levelPrefix = "levelname:";

  private static final String baseURL = "https://issues.apache.org/jira/browse/";

  private static final Pattern jiraKeyPattern = Pattern.compile("(UIMA-\\p{Digit}+)");

  private static final Pattern jiraAbstractPattern = Pattern.compile("<title>(.*)</title>");

  /**
   * Main method of the LevelIssueAnalyzer, starts the Jira issue detection.
   * 
   * @param args -
   *          command line parameter specifies the last level name that should be used for the for
   *          the analysis
   */
  public static void main(String[] args) {

    String levelname = null;
    String analyzeData = null;
    LevelIssueAnalyzer analyzer = new LevelIssueAnalyzer();

    if (args.length != 1) {
      System.out.println("Usage: LevelIssueAnalyzer <last level name>");
      System.exit(1);
    } else {
      levelname = levelPrefix + args[0];
    }

    // analyze http://svn.apache.org/repos/asf/incubator/uima/ to search all comments for level
    // revision
    int retry = 0;
    do {
      if (retry == 1) {
        System.out.println("Error getting repository logs, retry one more time.");
      }
      analyzeData = getSvnLogs("http://svn.apache.org/repos/asf/incubator/uima/uimaj");
      retry++;
    } while (analyzeData == null && retry <= 1);

    if (analyzeData == null) {
      System.out
              .println("Error getting repository logs for http://svn.apache.org/repos/asf/incubator/uima/uimaj");
      System.exit(-1);
    }

    // get all message comments from logfile as NodeList
    NodeList uimajRevisions = analyzer.evaluate("/log/logentry/msg/text()", analyzeData);

    // search for revision for the specified level in log message comments
    int levelRevision = -1;
    for (int i = 0; i < uimajRevisions.getLength(); i++) {
      String message = uimajRevisions.item(i).getNodeValue();
      if (message.indexOf(levelname) > 0) {
        // navigate to the <logentry> node
        NamedNodeMap attributes = uimajRevisions.item(i).getParentNode().getParentNode()
                .getAttributes();
        // get value for <logentry revision=xx> attribute
        levelRevision = Integer.parseInt(attributes.getNamedItem("revision").getNodeValue());
      }
    }
    // if levelRevision != -1 we found the revision for the specified level

    HashSet jiraIssues = new HashSet();

    if (levelRevision > 0) {

      // analyze http://svn.apache.org/repos/asf/incubator/uima/uimaj to search all comments for
      // level revision
      retry = 0;
      do {
        if (retry == 1) {
          System.out.println("Error getting repository logs, retry one more time.");
        }
        analyzeData = getSvnLogs("http://svn.apache.org/repos/asf/incubator/uima/uimaj/trunk");
        retry++;
      } while (analyzeData == null && retry <= 1);

      if (analyzeData == null) {
        System.out
                .println("Error getting repository logs for http://svn.apache.org/repos/asf/incubator/uima/uimaj/trunk");
        System.exit(-1);
      }

      // get all message comments from logfile as NodeList
      NodeList trunkRevisions = analyzer.evaluate("/log/logentry/msg/text()", analyzeData);

      // get all revisions higher than level revision
      for (int i = 0; i < trunkRevisions.getLength(); i++) {
        NamedNodeMap attributes = trunkRevisions.item(i).getParentNode().getParentNode()
                .getAttributes();
        // get value for <logentry revision=xx> attribute
        int revisionNumber = Integer.parseInt(attributes.getNamedItem("revision").getNodeValue());
        if (revisionNumber > levelRevision) {
          // get message comment
          String message = trunkRevisions.item(i).getNodeValue();
          Matcher m = jiraKeyPattern.matcher(message);
          if (m.find()) {
            // add Jira issue to list
            jiraIssues.add(m.group(0));
          }
        }
      }

      // get JIRA abstracts for issues
      Iterator it = jiraIssues.iterator();
      try {

        BufferedWriter outputWriter = new BufferedWriter(new FileWriter("levelIssues.txt", false));

        while (it.hasNext()) {
          // get JIRA issue key
          String jiraIssue = (String) it.next();
          // create JIRA issue URL
          URL url = new URL(baseURL + jiraIssue);
          // get URL content from web
          URLConnection connection = url.openConnection();
          BufferedInputStream inContent = new BufferedInputStream(connection.getInputStream());
          byte[] contentBuffer = new byte[500];
          inContent.read(contentBuffer, 0, 500);
          String content = new String(contentBuffer, "UTF-8");
          // try to find title that contains the JIRA issue abstract in the web content
          Matcher m = jiraAbstractPattern.matcher(content);
          String jiraIssueAbstract = "";
          if (m.find()) {
            // retrieve abstract
            jiraIssueAbstract = m.group(1);
          }
          System.out.println("https://issues.apache.org/jira/browse/" + jiraIssue + " : "
                  + jiraIssueAbstract);
          outputWriter.write("https://issues.apache.org/jira/browse/" + jiraIssue + " : "
                  + jiraIssueAbstract + "\n");
        }

        System.out.println("\nJira issues written to file: levelIssues.txt");
        outputWriter.flush();
        outputWriter.close();
      } catch (Exception ex) {
        ex.printStackTrace();
      }

    }
  }

  /**
   * evaluate the xpath expression for the xml string data and returns a nodelist with the matches.
   * 
   * @param expression xpath expression
   * @param analyzeData xml string to analyze
   * 
   * @return NodeList that contains the nodes extracted from the xml
   */
  private NodeList evaluate(String expression, String analyzeData) {

    XPath xpath = XPathFactory.newInstance().newXPath();
    NodeList saxNodeList = null;
    try {
      saxNodeList = (NodeList) xpath.evaluate(expression, new InputSource(new StringReader(
              analyzeData)), XPathConstants.NODESET);
    } catch (XPathExpressionException ex) {
      ex.printStackTrace();
      return null;
    }

    return saxNodeList;
  }

  /**
   * retrieves the svn commit logs for the specified svn repository
   * 
   * @param repository - svn repository path
   * 
   * @return returns the commit logs as XML representation
   */
  private static String getSvnLogs(String repository) {

    String line = null;
    boolean error = false;
    StringBuffer buffer = new StringBuffer();

    System.out.println("Getting logs for: " + repository);
    // execute log command
    String[] command = { "svn", "log", repository, "--xml" };
    try {

      Process process = Runtime.getRuntime().exec(command);
      
      // check error stream
      BufferedInputStream errorStream = new BufferedInputStream(process.getErrorStream());
      if (errorStream.available() > 0) {
        BufferedReader errorReader = new BufferedReader(new InputStreamReader(errorStream));
        while ((line = errorReader.readLine()) != null) {
          System.out.println(line);
          error = true;
        }
      }

      if (error) {
        System.out.println("Error while getting logs");
        System.exit(-1);
      }

      // check output stream
      BufferedReader inputStream = new BufferedReader(new InputStreamReader(process
              .getInputStream()));
      while ((line = inputStream.readLine()) != null) {
        buffer.append(line);
      }
    } catch (IOException ex) {
      ex.printStackTrace();
    } 

    String content = buffer.toString();
    if (!content.startsWith("<?xml")) {
      return null;
    }
    else if(content.equals("<?xml version=\"1.0\" encoding=\"utf-8\"?><log>")) {
      return null;
    }

    System.out.println("Done getting logs for: " + repository + "\n");

    return content;
  }
}