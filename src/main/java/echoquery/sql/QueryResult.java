package echoquery.sql;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;

import com.facebook.presto.sql.tree.AstVisitor;
import com.facebook.presto.sql.tree.ComparisonExpression;
import com.facebook.presto.sql.tree.DefaultTraversalVisitor;
import com.facebook.presto.sql.tree.FunctionCall;
import com.facebook.presto.sql.tree.GroupingElement;
import com.facebook.presto.sql.tree.LogicalBinaryExpression;
import com.facebook.presto.sql.tree.LongLiteral;
import com.facebook.presto.sql.tree.QualifiedNameReference;
import com.facebook.presto.sql.tree.QuerySpecification;
import com.facebook.presto.sql.tree.SortItem;
import com.facebook.presto.sql.tree.StringLiteral;

import echoquery.utils.SlotUtil;
import echoquery.utils.TranslationUtils;

/**
 * The QueryResult class contains a status of a query request and a message for
 * the end user.
 */

public class QueryResult {
  enum Status {
    SUCCESS,
    REPAIR_REQUEST,
    FAILURE
  }
  private final Status status;
  private final String message;

  public QueryResult(Status status, String message) {
    this.status = status;
    this.message = message;
  }

  public Status getStatus() {
    return status;
  }

  public String getMessage() {
    return message;
  }

  /**
   * Builds a QueryResult out of a JDBC result set, and original request object.
   * @param request
   * @param result
   * @return A successful result where the message is the result of the query
   *    is in end-user natural language.
   */
  public static QueryResult of(QueryRequest request, ResultSet result) {
    // Extract the results from the ResultSet.
    final Optional<Double> singleValue;
    List<Entry<String, Double>> groupByValues = new ArrayList<>();

    try {
      // If there was a group by, we expect two columns and many rows.
      if (request.getGroupByColumn().getColumn().isPresent()) {
        result.first();
        // Add all of them to the list of entries.
        while (!result.isAfterLast()) {
          groupByValues.add(new AbstractMap.SimpleEntry<>(
              result.getString(1), result.getDouble(2)));
          result.next();
        }
        singleValue = Optional.empty();
      } else {
        // Otherwise just populate the single value.
        result.first();
        singleValue = Optional.ofNullable(result.getDouble(1));
      }
    } catch (SQLException e) {
      e.printStackTrace();
      return null;
    }

    return new QueryResult(
        Status.SUCCESS, translateResults(request, singleValue, groupByValues));
  }

  /**
   * Translates request and parsed results into a natural language String.
   * @param request
   * @param singleValue Empty if there's a group by, not otherwise.
   * @param groupByValues
   * @return A natural language string conveying the results.
   */
  public static String translateResults(
      QueryRequest request,
      Optional<Double> singleValue,
      List<Entry<String, Double>> groupByValues) {
    /**
     * Visit each node in the tree and convert it to natural language. Results
     * in a sentence form of the query.
     */
    StringBuilder translation = new StringBuilder();

    /**
     * The boolean here signifies when to include the contents. Right now
     * is included except for QualifiedNameReferences in select
     * clauses (as they correspond to our internal book-keeping of group-bys).
     */
    AstVisitor<Void, Boolean> translator =
        new DefaultTraversalVisitor<Void, Boolean>() {

      @Override
      protected Void visitQuerySpecification(
          QuerySpecification node, Boolean capture) {
        // By default do not capture any qualified names in select clauses.
        process(node.getSelect(), false);

        if (node.getFrom().isPresent()) {
          // Use what the user referenced, ignoring any complicated join
          // inference we did to get it to work.
          translation.append("in the ")
              .append(request.getFromTable().get())
              .append(" table");
        }
        if (node.getWhere().isPresent()) {
            translation.append(" where the value in ");
            process(node.getWhere().get(), true);
        }
        for (GroupingElement groupingElement : node.getGroupBy()) {
            process(groupingElement, true);
        }
        if (node.getHaving().isPresent()) {
            process(node.getHaving().get(), true);
        }
        for (SortItem sortItem : node.getOrderBy()) {
            process(sortItem, true);
        }
        return null;
      }

      @Override
      public Void visitFunctionCall(FunctionCall node, Boolean capture) {
        String aggregation = request.getAggregationFunc().get();
        if (aggregation.equals("COUNT")) {
          double value;
          if (singleValue.isPresent()) {
            value = singleValue.get();
          } else {
            value = groupByValues.get(0).getValue();
          }
          if (value == 1) {
            translation.append("There is ")
              .append(TranslationUtils.convert(value))
              .append(" row ");
          } else {
            translation.append("There are ")
              .append(TranslationUtils.convert(value))
              .append(" rows ");
          }
        } else {
          translation.append("The ")
              .append(SlotUtil.aggregationFunctionToEnglish(aggregation))
              .append(" of the ");
          // Only accept qualified names in select clauses that belong to a
          // aggregation function.
          process(node.getArguments().get(0), true);
          translation.append(" column ");
        }
        return null;
      }

      @Override
      public Void visitLogicalBinaryExpression(
          LogicalBinaryExpression node, Boolean capture) {
        process(node.getLeft(), true);
        switch (node.getType()) {
          case AND:
            translation.append(" and ");
            break;
          case OR:
            translation.append(" or ");
            break;
        }
        process(node.getRight(), true);
        return null;
      }

      @Override
      public Void visitComparisonExpression(
          ComparisonExpression node, Boolean capture) {
        translation.append("the ");
        process(node.getLeft(), true);
        translation.append(" column is")
            .append(SlotUtil.comparisonTypeToEnglish(node.getType()));
        process(node.getRight(), true);
        return null;
      }

      @Override
      public Void visitQualifiedNameReference(
          QualifiedNameReference node, Boolean capture) {
        if (capture) {
          translation.append(node.getSuffix());
        }
        return null;
      }

      @Override
      public Void visitStringLiteral(StringLiteral node, Boolean capture) {
        translation.append(node.getValue());
        return null;
      }

      @Override
      public Void visitLongLiteral(LongLiteral node, Boolean capture) {
        translation.append(TranslationUtils.convert(node.getValue()));
        return null;
      }
    };
    translator.process(request.getQuery(), true);

    /**
     * At this point the body of the query has been translated, but we need to
     * append one or more results to the end now.
     */

    // If it's count and it only had a single value, we're all set.
    if (request.getAggregationFunc().get().equals("COUNT")) {
      // Otherwise if its count and a group by, we then have to say the group of
      // the first result we already stated, as well as add on the rest of the
      // results by group.
      if (!singleValue.isPresent()) {
        translation.append(" for the ")
            .append(request.getGroupByColumn().getColumn().get())
            .append(" ")
            .append(groupByValues.get(0).getKey());
        for (int i = 1; i < groupByValues.size(); i++) {
          translation.append(", ");
          if (i == groupByValues.size() - 1) {
            translation.append("and ");
          }
          translation
              .append(TranslationUtils.convert(groupByValues.get(i).getValue()))
              .append((groupByValues.get(i).getValue() == 1)
                  ? " row for " : " rows for ")
              .append(groupByValues.get(i).getKey());
        }
      }
    } else {
      // For all other aggregations, if there's only a single value we simply
      // tack it on.
      if (singleValue.isPresent()) {
        translation.append(" is ")
            .append(TranslationUtils.convert(singleValue.get()));
      }
      // Otherwise we have to tack on all of the remaining values.
      for (int i = 0; i < groupByValues.size(); i++) {
        if (i == 0) {
          translation.append(" is ")
              .append(TranslationUtils.convert(groupByValues.get(i).getValue()))
              .append(" for the ")
              .append(request.getGroupByColumn().getColumn().get())
              .append(" ")
              .append(groupByValues.get(i).getKey());
        } else {
          translation.append(", ");
          if (i == groupByValues.size() - 1) {
            translation.append("and ");
          }
          translation
              .append(TranslationUtils.convert(groupByValues.get(i).getValue()))
              .append(" for ")
              .append(groupByValues.get(i).getKey());
        }
      }
    }
    translation.append(".");
    return translation.toString();
  }
}

