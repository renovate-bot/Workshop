package com.couchbase.updownapp;

import com.couchbase.client.java.AsyncBucket;
import com.couchbase.client.java.document.JsonDocument;
import com.couchbase.client.java.document.json.JsonObject;
import com.couchbase.client.java.query.*;

import static com.couchbase.client.java.query.Select.*;
import static com.couchbase.client.java.query.dsl.Expression.x;
import static com.couchbase.client.java.query.dsl.Expression.s;

import com.couchbase.client.java.query.consistency.ScanConsistency;
import com.couchbase.client.java.query.dsl.Sort;
import rx.Observable;

import java.util.Date;

public class Presentation {

  private String title = "";
  private String key = null;
  private Date createdAt = new Date();
  private int upVotes = 0;
  private int downVotes = 0;
  private boolean persisted = false;
  private JsonDocument loadedDocument = null;

  private static AsyncBucket client;

  static {
    client = CouchbaseConnectionFactory.getDefaultConnection();
  }


  public String getKey() {
    return key;
  }

  private void setKey(String key) {
    // key can only be set once for a document, so saving on a key change is useless,
    // so no modifying persisted here
    this.key = key;
  }

  public void setTitle(String title) {
    this.persisted = false;
    this.title = title;
  }

  public void setCreatedAt(Date createdAt) {
    this.persisted = false;
    this.createdAt = createdAt;
  }

  public void setUpVotes(int upVotes) {
    this.persisted = false;
    this.upVotes = upVotes;
  }

  public void setDownVotes(int downVotes) {
    this.persisted = false;
    this.downVotes = downVotes;
  }

  public void setPersisted(boolean persisted) {
    this.persisted = persisted;
  }

  private JsonDocument getLoadedDocument() {
    return loadedDocument;
  }

  private void setLoadedDocument(JsonDocument loadedDocument) {
    this.loadedDocument = loadedDocument;
  }

  public String getTitle() {
    return title;
  }

  public Date getCreatedAt() {
    return createdAt;
  }

  public int getUpVotes() {
    return upVotes;
  }

  public int getDownVotes() {
    return downVotes;
  }

  private boolean isPersisted() {
    return persisted;
  }

  /*
   * This method uses Lambdas, available starting Java 8. If you are note familiar with Lambdas,
   * take a look at the following line of code:
   *
   *         return op.map(jd -> fromJsonDocument(jd));
   *
   * It would look like this without using Lambdas:
   *
   *        return op.map(new Func1<JsonDocument, Presentation>() {
   *           @Override
   *           public Presentation call(JsonDocument jsonDocument) {
   *               return fromJsonDocument(jsonDocument);
   *          }
   *       });
   *
   * For more information about Lambdas, please follow this link:
   *  http://docs.oracle.com/javase/tutorial/java/javaOO/lambdaexpressions.html
   *
   */
  public Observable<Presentation> save() {
    if(isPersisted()) {
      return Observable.just(this);
    }

    Observable<JsonDocument> op;
    if(loadedDocument != null) {
      op = client.replace(toJsonDocument());
    } else {
      op = client.insert(toJsonDocument());
    }

    return op.map(jd -> fromJsonDocument(jd));
  }

  public static Observable<Presentation> find(String key) {
    return client.get(key).map(jd -> fromJsonDocument(jd));
  }

  public static void setupIndexes() {
    AsyncQueryResult res = client.query(Query.simple("CREATE PRIMARY INDEX ON " + client.name())).toBlocking().single();
    for (JsonObject error : res.errors().toBlocking().toIterable()) {
      if (!error.getString("msg").contains("exists")) {
        throw new RuntimeException("Couldn't create index: " + error);
      }
    }
  }

  /*
   * Relies on N1QL index. Ensure that setupIndexes() is called.
   */
  public static Observable<Presentation> findAll() {
    Statement s = select("type", "title", "upVotes", "downVotes", "createdAt",
            "STR_TO_UTC(createdAt) AS utcStamp",
            "META(default).id AS docId")
            .from(client.name())
            .where(x("type").eq(s("presentation")))
            .orderBy(Sort.asc("utcStamp"));
    QueryParams params = QueryParams.build().consistency(ScanConsistency.REQUEST_PLUS);

    System.err.println(s.toString());
    return client.query(Query.simple(s, params))
            .flatMap(AsyncQueryResult::rows)
            .map(Presentation::fromQueryRow);
  }

  private static Presentation fromQueryRow(AsyncQueryRow row) {
    JsonObject json = row.value();
    return fromJsonDocument(JsonDocument.create(json.getString("docId"), json));
  }

  private static Presentation fromJsonDocument(JsonDocument jsonDocument) {
    Presentation presentation = new Presentation();
    JsonObject content = jsonDocument.content();
    presentation.setKey(jsonDocument.id()); // one time key -> always key!
    presentation.setTitle(content.getString("title"));
    presentation.setUpVotes(content.getInt("upVotes"));
    presentation.setDownVotes(content.getInt("downVotes"));
    presentation.setCreatedAt(new Date(content.getLong("createdAt")));
    presentation.setLoadedDocument(jsonDocument);
    presentation.setPersisted(true);
    return presentation;
  }

  private JsonDocument toJsonDocument() {
    JsonObject content = JsonObject.empty();
    if(getLoadedDocument() == null && getKey() == null && !getTitle().isEmpty()) {
      // generate a key if we don't have one!
      setKey("p::" + getTitle().replaceAll(" ", "_").toLowerCase());
    }
    content.put("title", getTitle());
    content.put("upVotes", getUpVotes());
    content.put("downVotes", getDownVotes());
    content.put("createdAt", getCreatedAt().getTime());
    content.put("type", "presentation");

    JsonDocument jsonDocument;
    if (getLoadedDocument() != null) {
      // this will reuse the key the loaded document had! So setting the key is futile!
      jsonDocument = JsonDocument.from(getLoadedDocument(), content);
    } else {
      jsonDocument = JsonDocument.create(getKey(), content);
    }
    return jsonDocument;
  }
}
