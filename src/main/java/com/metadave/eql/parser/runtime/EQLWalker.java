/*
 * -------------------------------------------------------------------
 *
 * Copyright (c) 2015 Dave Parfitt
 *
 * This file is provided to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License.  You may obtain
 * a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * -------------------------------------------------------------------
 */

package com.metadave.eql.parser.runtime;

import com.metadave.eql.parser.*;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeProperty;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.index.query.*;
import org.elasticsearch.search.sort.SortOrder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.elasticsearch.index.query.QueryBuilders.*;

public class EQLWalker extends EQLBaseListener {
    ParseTreeProperty<Object> values = new ParseTreeProperty<Object>();

    public RuntimeContext runtimeCtx = null;

    public EQLWalker(RuntimeContext ctx) {
        this.runtimeCtx = ctx;
    }

    public void setValue(ParseTree node, Object value) {
        values.put(node, value);
    }

    public Object getValue(ParseTree node) {
        return values.get(node);
    }

    @Override
    public void exitConnect_stmt(EQLParser.Connect_stmtContext ctx) {

        String clusterName = null;
        if(ctx.clusterName != null) {
            clusterName = ctx.clusterName.getText();
            Settings settings = ImmutableSettings.settingsBuilder()
                    .put("cluster.name", clusterName).build();
            runtimeCtx.client = new TransportClient(settings);
        } else {
            runtimeCtx.client = new TransportClient();
        }

        List<EQLParser.HostportContext> hps = ctx.hps;
        for(EQLParser.HostportContext hpc : hps) {
            HostPort hp = (HostPort)getValue(hpc);
            TransportClient tc = (TransportClient) runtimeCtx.client;
            tc.addTransportAddress(new InetSocketTransportAddress(hp.getHost(), hp.getPort()));
        }

    }

    @Override
    public void exitHostport(EQLParser.HostportContext ctx) {
        String host = ctx.host.getText();
        String port = ctx.port.getText();
        setValue(ctx, new HostPort(host, Integer.parseInt(port)));
    }



    @Override
    public void exitQuery_stmt(EQLParser.Query_stmtContext ctx) {
        if(runtimeCtx.client == null) {
            throw new RuntimeException("Not connected");
        }
        String key = ctx.key.getText();

        // determine which type of query we have
        QueryBuilder qb = matchAllQuery();

        if(ctx.filter_stmt() != null) {
            FilterBuilder fb = (FilterBuilder)getValue(ctx.filter_stmt());
            qb = QueryBuilders.filteredQuery(qb, fb);
        }

        // SearchResponse response = runtimeCtx.client.prepareSearch(key).setQuery(qb).execute().actionGet();
        SearchRequestBuilder b = runtimeCtx.client.prepareSearch(key).setQuery(qb);

        if(ctx.field_list() != null) {
            List<String> fields = (List<String>)getValue(ctx.field_list());
            for(String f: fields) {
                b.addField(f);
            }
        }

        Return r = (Return)getValue(ctx.return_stmt());
        if(r != null) {
            if(r.getSize() != null) {
                b = b.setSize(r.getSize());
            }

            if(r.getLower() != null) {
                b.setFrom(r.getLower());
            }
        }


        if(ctx.sort_stmt() != null) {
            List<QuerySort> sorts = (List<QuerySort>)getValue(ctx.sort_stmt());
            for(QuerySort s: sorts) {
                switch(s.getOrderBy()) {
                    case Ascending:
                        b = b.addSort(s.getKey(), SortOrder.ASC); break;
                    case Descending:
                        b = b.addSort(s.getKey(), SortOrder.DESC); break;
                    default:
                        // wat
                }
            }
        }

        System.out.println("QUERY :" + b.toString());
        System.out.println("RESPONSE:");
        SearchResponse response = b.execute().actionGet();
        System.out.println(response);
    }


    @Override
    public void exitSort_stmt(EQLParser.Sort_stmtContext ctx) {
        QuerySort sorts[] = new QuerySort[ctx.keys.size()];
        int keyindex = 0;
        for(Token k :ctx.keys) {
            sorts[keyindex] = new QuerySort();
            sorts[keyindex].setKey(k.getText());
            keyindex++;
        }

        int dirindex = 0;
        for(EQLParser.AscdescContext ad: ctx.sorts) {
            QuerySort.Order o = (QuerySort.Order)getValue(ad);
            sorts[dirindex].setOrderBy(o);
            dirindex++;
        }
        List<QuerySort> l = Arrays.asList(sorts);
        setValue(ctx, l);
    }

    @Override
    public void exitAscdesc(EQLParser.AscdescContext ctx) {
        if(ctx.asc != null) {
            setValue(ctx, QuerySort.Order.Ascending);
        } else if(ctx.desc != null) {
            setValue(ctx, QuerySort.Order.Descending);
        } else {
            setValue(ctx, QuerySort.Order.None);
        }
    }

    @Override
    public void exitReturn_stmt(EQLParser.Return_stmtContext ctx) {
        Integer count = null;
        Integer lower = null;
        Integer upper = null;

        if(ctx.size != null) {
            count = Integer.parseInt(ctx.size.getText());
        }

        if(ctx.lower != null) {
            lower = Integer.parseInt(ctx.lower.getText());
        }

        Return r = new Return(count, lower, upper);
        setValue(ctx, r);
    }


    @Override
    public void exitFilter_stmt(EQLParser.Filter_stmtContext ctx) {
        // pass it up
        Object o = getValue(ctx.filter_pred());
        setValue(ctx, o);
    }

    @Override
    public void exitFilter_pred(EQLParser.Filter_predContext ctx) {
        if(ctx.LPAREN() != null) {
            setValue(ctx, getValue(ctx.childpred));
            return;
        }

        FilterBuilder first;
        if (ctx.intval != null) {
            int i = Integer.parseInt(ctx.intval.getText());
            first = FilterBuilders.termFilter(ctx.name.getText(), i);
        } else {
            String v = ParseUtils.stripQuotes(ctx.stringval.getText());
            first = FilterBuilders.termFilter(ctx.name.getText(), v);
        }

        if(ctx.filter_rest() != null) {
            FilterBuilder restBuilder = (FilterBuilder)getValue(ctx.filter_rest());
            if(restBuilder instanceof AndFilterBuilder) {
                AndFilterBuilder afb = (AndFilterBuilder)restBuilder;
                afb.add(first);
            } else if(restBuilder instanceof OrFilterBuilder) {
                OrFilterBuilder ofb = (OrFilterBuilder)restBuilder;
                ofb.add(first);
            }
            setValue(ctx, restBuilder);
        } else {
            // scalar filter
            setValue(ctx, first);
        }
    }

    @Override
    public void exitFilter_rest(EQLParser.Filter_restContext ctx) {
        if(ctx.ands.size() > 0) {
            AndFilterBuilder afb = new AndFilterBuilder();
            for(EQLParser.Filter_predContext a : ctx.ands) {
                afb.add((FilterBuilder)getValue(a));
            }
            setValue(ctx, afb);
        } else if (ctx.ors.size() > 0) {
            OrFilterBuilder ofb = new OrFilterBuilder();
            for(EQLParser.Filter_predContext o : ctx.ors) {
                ofb.add((FilterBuilder)getValue(o));
            }
            setValue(ctx, ofb);
        }
    }


    @Override
    public void exitField_list(EQLParser.Field_listContext ctx) {
        List<String> fields = new ArrayList<String>();
        for(Token t : ctx.fields) {
            fields.add(t.getText());
        }
        setValue(ctx, fields);
    }
}
