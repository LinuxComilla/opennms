package org.opennms.web.filter;

import org.hibernate.Hibernate;
import org.hibernate.criterion.*;

import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

public class Whatever {

//    // EqualsFilter
//    public Predicate getPredicate(Root<X> root, CriteriaBuilder builder) {
//        return builder.equal(root.get(getPropertyName()), getValue());
//    }
//
//    // AndFilter
//    public Criterion getCriterion() {
//        Conjunction conjunction = Restrictions.conjunction();
//        for(Filter filter : getFilters()) {
//            conjunction.add(filter.getCriterion());
//        }
//        return conjunction;
//    }
//
//    // BetweenFilter
//    public Criterion getCriterion() {
//        return Restrictions.between(getPropertyName(), getFirst(), getLast());
//    }
//
//    // GreaterThanFilter
//    public Criterion getCriterion() {
//        return Restrictions.gt(getPropertyName(), getValue());
//    }
//
//    // InFilter
//    public Criterion getCriterion() {
//        return Restrictions.in(getPropertyName(), getValuesAsList());
//    }
//
//    // IpLikeFilter
//    public Criterion getCriterion() {
//        return Restrictions.sqlRestriction("iplike( {alias}."+getPropertyName()+", ?)", getValue(), Hibernate.STRING);
//    }
//
//    // LessThanFilter
//    public Criterion getCriterion() {
//        return Restrictions.le(getPropertyName(), getValue());
//    }

    // LikeFilter
//    public Criterion getCriterion() {
//        return Restrictions.like(getPropertyName(), getValue());
//    }

    //NoSubstringFilter
//    public Criterion  getCriterion() {
//        return Restrictions.not(Restrictions.ilike(getPropertyName(), getValue(), MatchMode.ANYWHERE));
//    }

    // NoSubstringFilter
//    public Criterion getCriterion() {
//        return Restrictions.or(Restrictions.ne(getPropertyName(), getValue()), Restrictions.isNull(getPropertyName()));
//    }

    //NotEqualsFilter
//    public Criterion getCriterion() {
//        return Restrictions.ne(getPropertyName(), getValue());
//    }
              // OrFilter
//    public Criterion getCriterion() {
//        Disjunction disjunction = Restrictions.disjunction();
//
//        for(Filter filter : getFilters()) {
//            disjunction.add(filter.getCriterion());
//        }
//
//        return disjunction;
//    }

    // SubstringFilter
//    public Criterion getCriterion() {
//        return Restrictions.ilike(getPropertyName(), getValue(), MatchMode.ANYWHERE);
//    }


}