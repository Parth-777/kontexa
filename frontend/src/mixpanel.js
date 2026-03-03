import mixpanel from "mixpanel-browser";

mixpanel.init("1c8e05bf02a8d82d3cb876f7b6f5b994", {
  debug: true,
  track_pageview: false
});

export default mixpanel;
