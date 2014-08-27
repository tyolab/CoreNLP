package edu.stanford.nlp.trees.international.spanish;

import java.io.*;
import java.util.*;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import edu.stanford.nlp.io.ReaderInputStream;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.ling.HasCategory;
import edu.stanford.nlp.ling.HasContext;
import edu.stanford.nlp.ling.HasIndex;
import edu.stanford.nlp.ling.HasLemma;
import edu.stanford.nlp.ling.HasTag;
import edu.stanford.nlp.ling.HasWord;
import edu.stanford.nlp.ling.Label;
import edu.stanford.nlp.ling.Sentence;
import edu.stanford.nlp.trees.LabeledScoredTreeFactory;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.trees.TreeFactory;
import edu.stanford.nlp.trees.TreeNormalizer;
import edu.stanford.nlp.trees.TreeReader;
import edu.stanford.nlp.trees.TreeReaderFactory;
import edu.stanford.nlp.trees.TreebankLanguagePack;
import edu.stanford.nlp.util.Generics;
import edu.stanford.nlp.util.PropertiesUtils;
import edu.stanford.nlp.util.StringUtils;
import edu.stanford.nlp.util.XMLUtils;

/**
 * A reader for XML format Spanish Treebank files.
 *
 * @author Jon Gauthier
 * @author Spence Green (original French XML reader)
 *
 */
public class SpanishXMLTreeReader implements TreeReader {

  private InputStream stream;
  private final TreeNormalizer treeNormalizer;
  private final TreeFactory treeFactory;
  private boolean simplifiedTagset;

  private static final String NODE_SENT = "sentence";

  private static final String ATTR_WORD = "wd";
  private static final String ATTR_LEMMA = "lem";
  private static final String ATTR_FUNC = "func";
  private static final String ATTR_NAMED_ENTITY = "ne";
  private static final String ATTR_POS = "pos";
  private static final String ATTR_POSTYPE = "postype";
  private static final String ATTR_ELLIPTIC = "elliptic";
  private static final String ATTR_PUNCT = "punct";

  private static final String EMPTY_LEAF = "-NONE-";

  private NodeList sentences;
  private int sentIdx;

  /**
   * Read parse trees from a Reader.
   *
   * @param in The <code>Reader</code>
   * @param simplifiedTagset If `true`, convert part-of-speech labels to a
   *          simplified version of the EAGLES tagset, where the tags do not
   *          include extensive morphological analysis
   * @param aggressiveNormalization Perform aggressive "normalization"
   *          on the trees read from the provided corpus documents:
   *          split multi-word tokens into their constituent words (and
   *          infer parts of speech of the constituent words).
   */
  public SpanishXMLTreeReader(Reader in, boolean simplifiedTagset,
                              boolean aggressiveNormalization) {
    TreebankLanguagePack tlp = new SpanishTreebankLanguagePack();

    this.simplifiedTagset = simplifiedTagset;

    stream = new ReaderInputStream(in, tlp.getEncoding());
    treeFactory = new LabeledScoredTreeFactory();
    treeNormalizer = new SpanishTreeNormalizer(simplifiedTagset,
                                               aggressiveNormalization);

    DocumentBuilder parser = XMLUtils.getXmlParser();
    try {
      final Document xml = parser.parse(stream);
      final Element root = xml.getDocumentElement();
      sentences = root.getElementsByTagName(NODE_SENT);
      sentIdx = 0;

    } catch (SAXException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public void close() {
    try {
      if(stream != null) {
        stream.close();
        stream = null;
      }
    } catch (IOException e) {
      //Silently ignore
    }
  }

  public Tree readTree() {
    Tree t = null;
    while(t == null && sentences != null && sentIdx < sentences.getLength()) {
      int thisSentenceId = sentIdx++;
      Node sentRoot = sentences.item(thisSentenceId);
      t = getTreeFromXML(sentRoot);

      if(t != null) {
        t = treeNormalizer.normalizeWholeTree(t, treeFactory);

        if(t.label() instanceof CoreLabel)
          ((CoreLabel) t.label()).set(CoreAnnotations.SentenceIDAnnotation.class,
                                      Integer.toString(thisSentenceId));
      }
    }
    return t;
  }

  private boolean isWordNode(Element node) {
    return node.hasAttribute(ATTR_WORD);
  }

  private boolean isEllipticNode(Element node) {
    return node.hasAttribute(ATTR_ELLIPTIC);
  }

  /**
   * Determine the part of speech of the given leaf node.
   *
   * Use some heuristics to make up for missing part-of-speech labels.
   */
  private String getPOS(Element node) {
    String pos = node.getAttribute(ATTR_POS);

    // Make up for some missing part-of-speech tags
    if (pos.equals("")) {
      String namedAttribute = node.getAttribute(ATTR_NAMED_ENTITY);
      if (namedAttribute.equals("date")) {
        return "w";
      } else if (namedAttribute.equals("number")) {
        return "z0";
      }

      String tagName = node.getTagName();
      if (tagName.equals("i")) {
        return "i";
      } else if (tagName.equals("r")) {
        return "rg";
      } else if (tagName.equals("z")) {
        return "z0";
      }

      // Handle icky issues related to "que"
      String posType = node.getAttribute(ATTR_POSTYPE);
      String word = getWord(node);
      if (tagName.equals("c") && posType.equals("subordinating")) {
        return "cs";
      } else if (tagName.equals("p") && posType.equals("relative")
                 && word.equalsIgnoreCase("que")) {
        return "pr0cn000";
      }

      if (simplifiedTagset) {
        // If we are using the simplfied tagset, we can make some more
        // broad inferences
        if (tagName.equals("a")) {
          return "aq0000";
        }
      }

      if (node.hasAttribute(ATTR_PUNCT)) {
        return "f";
      }
    }

    return pos;
  }

  private String getWord(Element node) {
    String word = node.getAttribute(ATTR_WORD);
    if (word.equals(""))
      return EMPTY_LEAF;

    return word.trim();
  }

  private Tree getTreeFromXML(Node root) {
    final Element eRoot = (Element) root;

    if (isWordNode(eRoot)) {
      return buildWordNode(eRoot);
    } else if (isEllipticNode(eRoot)) {
      return buildEllipticNode(eRoot);
    } else {
      List<Tree> kids = new ArrayList<Tree>();
      for(Node childNode = eRoot.getFirstChild(); childNode != null;
          childNode = childNode.getNextSibling()) {
        if(childNode.getNodeType() != Node.ELEMENT_NODE) continue;
        Tree t = getTreeFromXML(childNode);
        if(t == null) {
          System.err.printf("%s: Discarding empty tree (root: %s)%n", this.getClass().getName(),childNode.getNodeName());
        } else {
          kids.add(t);
        }
      }

      String rootLabel = eRoot.getNodeName().trim();

      Tree t = (kids.size() == 0) ? null : treeFactory.newTreeNode(treeNormalizer.normalizeNonterminal(rootLabel), kids);

      return t;
    }
  }

  /**
   * Build a parse tree node corresponding to the word in the given XML node.
   */
  private Tree buildWordNode(Node root) {
    Element eRoot = (Element) root;

    // TODO make sure there are no children as well?

    String posStr = getPOS(eRoot);
    posStr = treeNormalizer.normalizeNonterminal(posStr);

    String lemma = eRoot.getAttribute(ATTR_LEMMA);
    String word = getWord(eRoot);

    String leafStr = treeNormalizer.normalizeTerminal(word);
    Tree leafNode = treeFactory.newLeaf(leafStr);
    if (leafNode.label() instanceof HasWord)
      ((HasWord) leafNode.label()).setWord(leafStr);
    if (leafNode.label() instanceof HasLemma && lemma != null)
      ((HasLemma) leafNode.label()).setLemma(lemma);

    List<Tree> kids = new ArrayList<Tree>();
    kids.add(leafNode);

    Tree t = treeFactory.newTreeNode(posStr, kids);
    if (t.label() instanceof HasTag) ((HasTag) t.label()).setTag(posStr);

    return t;
  }

  /**
   * Build a parse tree node corresponding to an elliptic node in the parse XML.
   */
  private Tree buildEllipticNode(Node root) {
    Element eRoot = (Element) root;
    String constituentStr = eRoot.getNodeName();

    List<Tree> kids = new ArrayList<Tree>();
    Tree leafNode = treeFactory.newLeaf(EMPTY_LEAF);
    if (leafNode.label() instanceof HasWord)
      ((HasWord) leafNode.label()).setWord(EMPTY_LEAF);

    kids.add(leafNode);
    Tree t = treeFactory.newTreeNode(constituentStr, kids);

    return t;
  }

  /**
   * Determine if the given tree contains a leaf which matches the
   * part-of-speech and lexical criteria.
   *
   * @param pos Regular expression to match part of speech (may be null,
   *     in which case any POS is allowed)
   * @param pos Regular expression to match word (may be null, in which
   *     case any word is allowed)
   */
  public static boolean shouldPrintTree(Tree tree, Pattern pos, Pattern word) {
    for(Tree t : tree) {
      if(t.isPreTerminal()) {
        CoreLabel label = (CoreLabel) t.label();
        String tpos = label.value();

        Tree wordNode = t.firstChild();
        CoreLabel wordLabel = (CoreLabel) wordNode.label();
        String tword = wordLabel.value();

        if((pos == null || pos.matcher(tpos).find())
           && (word == null || word.matcher(tword).find()))
          return true;
      }
    }
    return false;
  }

  private static String toString(Tree tree, boolean plainPrint) {
    if (!plainPrint)
      return tree.toString();

    StringBuilder sb = new StringBuilder();
    List<Tree> leaves = tree.getLeaves();
    for (Tree leaf : leaves)
      sb.append(((CoreLabel) leaf.label()).value()).append(" ");

    return sb.toString();
  }

  private static String usage() {
    StringBuilder sb = new StringBuilder();
    String nl = System.getProperty("line.separator");
    sb.append(String.format("Usage: java %s [OPTIONS] file(s)%n%n", SpanishXMLTreeReader.class.getName()));
    sb.append("Options:").append(nl);
    sb.append("   -help: Print this message").append(nl);
    sb.append("   -plain: Output corpus in plaintext rather than as trees").append(nl);
    sb.append("   -searchPos posRegex: Only print sentences which contain a token whose part of speech matches the given regular expression").append(nl);
    sb.append("   -searchWord wordRegex: Only print sentences which contain a token which matches the given regular expression").append(nl);
    return sb.toString();
  }

  private static Map<String, Integer> argOptionDefs() {
    Map<String, Integer> argOptionDefs = Generics.newHashMap();
    argOptionDefs.put("help", 0);
    argOptionDefs.put("plain", 0);
    argOptionDefs.put("searchPos", 1);
    argOptionDefs.put("searchWord", 1);
    return argOptionDefs;
  }

  /**
   * For debugging.
   *
   * @param args
   */
  public static void main(String[] args) {
    final Properties options = StringUtils.argsToProperties(args, argOptionDefs());
    if(args.length < 1 || options.containsKey("help")) {
      System.err.println(usage());
      return;
    }

    Pattern posPattern = options.containsKey("searchPos")
      ? Pattern.compile(options.getProperty("searchPos")) : null;
    Pattern wordPattern = options.containsKey("searchWord")
      ? Pattern.compile(options.getProperty("searchWord")) : null;
    boolean plainPrint = PropertiesUtils.getBool(options, "plain", false);

    String[] remainingArgs = options.getProperty("").split(" ");
    List<File> fileList = new ArrayList<File>();
    for(int i = 0; i < remainingArgs.length; i++)
      fileList.add(new File(remainingArgs[i]));

    TreeReaderFactory trf = new SpanishXMLTreeReaderFactory(true, true);
    int totalTrees = 0;
    Set<String> morphAnalyses = Generics.newHashSet();
    try {
      for(File file : fileList) {
        TreeReader tr = trf.newTreeReader(new BufferedReader(new InputStreamReader(new FileInputStream(file), "ISO-8859-1")));

        Tree t;
        int numTrees;
        String canonicalFileName = file.getName().substring(0, file.getName().lastIndexOf('.'));

        for(numTrees = 0; (t = tr.readTree()) != null; numTrees++) {
          if (!shouldPrintTree(t, posPattern, wordPattern))
            continue;

          String ftbID = ((CoreLabel) t.label()).get(CoreAnnotations.SentenceIDAnnotation.class);
          String output = toString(t, plainPrint);

          System.out.printf("%s-%s\t%s%n", canonicalFileName, ftbID, output);
          List<Label> leaves = t.yield();
          for(Label label : leaves) {
            if(label instanceof CoreLabel)
              morphAnalyses.add(((CoreLabel) label).originalText());
          }
        }

        tr.close();
        System.err.printf("%s: %d trees%n",file.getName(),numTrees);
        totalTrees += numTrees;
      }

//wsg2011: Print out the observed morphological analyses
//      for(String analysis : morphAnalyses)
//        System.err.println(analysis);

      System.err.printf("%nRead %d trees%n",totalTrees);

    } catch (FileNotFoundException e) {
      e.printStackTrace();

    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
