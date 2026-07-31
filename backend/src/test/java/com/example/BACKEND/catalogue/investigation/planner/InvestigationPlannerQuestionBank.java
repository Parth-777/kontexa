package com.example.BACKEND.catalogue.investigation.planner;

import java.util.List;

/**
 * Business investigation questions for planner quality validation (n &gt;= 50).
 */
final class InvestigationPlannerQuestionBank {

    private InvestigationPlannerQuestionBank() {}

    static List<String> questions() {
        return List.of(
                // Temporal contrasts
                "Why did revenue increase month over month?",
                "Why did total trip revenue decline last month?",
                "What caused the spike in tips in March compared to February?",
                "Why did average fare drop between Q1 and Q2?",
                "What explains the week-over-week increase in trip volume?",
                "Why did airport revenue fall after the holiday season?",
                "What drove the increase in total_amount from January to February?",
                "Why are tips lower this month than last month?",
                "What explains revenue growth year over year?",
                "Why did trip revenue decrease in the most recent month?",
                "What caused fare amounts to rise between last week and this week?",
                "Why did total revenue change after the fare adjustment period?",
                "What explains the drop in tips since the start of the quarter?",
                "Why did average trip distance revenue increase over the past month?",
                "What drove the decline in total_amount compared to the prior month?",

                // Partition contrasts
                "Why do payment type 1 users generate higher tips than payment type 2?",
                "Why are airport trips more expensive than non-airport trips?",
                "Why is vendor 1 outperforming vendor 2 on total revenue?",
                "Why does the Manhattan region earn more than Brooklyn?",
                "Why are credit card trips tipping more than cash trips?",
                "Why is store_and_fwd_flag Y associated with higher fares than N?",
                "Why do long pickup zones generate more revenue than short ones?",
                "Why are trips with airport fees higher than trips without?",
                "Why does payment type 1 have higher average fare than payment type 2?",
                "Why is region Queens underperforming region Manhattan on tips?",
                "Why do vendor 2 trips have lower total_amount than vendor 1?",
                "Why are dropoff zone 100 trips more expensive than zone 200?",
                "Why do cash payments show lower tips than card payments?",
                "Why is Brooklyn revenue lower than Manhattan revenue?",
                "Why are forwarded trips generating different tips than non-forwarded trips?",

                // Quantile / extreme contrasts
                "Why are high-value customers different from low-value customers?",
                "What distinguishes top-spending customers from bottom-spending customers?",
                "Why do the highest-revenue vendors differ from the lowest-revenue vendors?",
                "What explains differences between the top 10% and bottom 10% of trips by fare?",
                "Why do heavy tippers behave differently from light tippers?",
                "What characteristics separate the highest-distance trips from the lowest?",
                "Why are the top revenue regions unlike the bottom revenue regions?",
                "What distinguishes customers in the top decile by total spend?",
                "Why do the best-performing payment types differ from the worst?",
                "What explains the gap between highest and lowest tipping zones?",

                // Norm-deviation contrasts
                "Why is this region unusually expensive?",
                "Why is Manhattan unusually high on average fare?",
                "Why is vendor 3 an outlier on total revenue?",
                "Why is payment type 2 unusually low on tips?",
                "Why is the airport segment abnormally high on total_amount?",
                "Why is region Brooklyn deviating from typical fare levels?",
                "Why is this pickup zone unusually profitable?",
                "Why are forwarded trips unusually low on tips compared to normal?",
                "Why is trip distance unusually high for this vendor?",
                "Why is average tip abnormally low for this region?",

                // Multi-factor / regional / margin style
                "Which factors explain regional revenue differences?",
                "What explains why enterprise-like regions convert better on tips?",
                "Why did margin-like fare metrics decline while volume grew?",
                "What factors most influenced the decline in average tip?",
                "Which characteristics distinguish high-tip regions from low-tip regions?",
                "What explains differences between pickup zones on revenue?",
                "Why are certain vendors driving the revenue gap across regions?",
                "What explains the increase in airport trip surcharges?",
                "Why do some regions show higher fare per mile than others?",
                "What drove the change in revenue composition after product launch?"
        );
    }
}
