package com.example.BACKEND.analytics.sql.model;

//package com.example.BACKEND.analytics.sql.model;

import java.util.ArrayList;
import java.util.List;


// QUERY BLUEPRINT


public class SqlQuery {

    private String table;
    private List<String> select = new ArrayList<>();
    private List<String> where = new ArrayList<>();
    private List<String> groupBy = new ArrayList<>();
    private Integer limit;
    private String from;
    private List<String> selectColumns = new ArrayList<>();
    private List<String> whereClauses = new ArrayList<>();

    // GROUP BY
    private List<String> groupByColumns = new ArrayList<>();


    public String getTable() {
        return table;
    }

    public void setTable(String table) {
        this.table = table;
    }

    public List<String> getSelect() {
        return select;
    }

    public List<String> getWhere() {
        return where;
    }

    public List<String> getGroupBy() {
        return groupBy;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getFrom() {
        return from;
    }

    public void addWhere(String condition) {
        this.whereClauses.add(condition);
    }

    public List<String> getWhereClauses() {
        return whereClauses;
    }
    public void addGroupBy(String column) {
        this.groupByColumns.add(column);
    }

    public List<String> getGroupByColumns() {
        return groupByColumns;
    }
    @Override
    public String toString() {
        return "SqlQuery{" +
                "from='" + from + '\'' +
                ", select=" + selectColumns +
                ", where=" + whereClauses +
                ", groupBy=" + groupByColumns +
                ", limit=" + limit +
                '}';
    }
   // private List<String> select = new ArrayList<>();

    public void addSelect(String expr) {
        select.add(expr);
    }

    //public List<String> getSelect() {
  //      return select;
}


