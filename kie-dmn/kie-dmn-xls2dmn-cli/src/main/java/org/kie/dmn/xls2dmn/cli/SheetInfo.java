package org.kie.dmn.xls2dmn.cli;

import java.util.List;

import org.kie.dmn.model.api.HitPolicy;

public class SheetInfo {
  private final HitPolicy hitPolicy;
  private final List<String> headers;

  public SheetInfo(HitPolicy hitPolicy, List<String> headers) {
    this.hitPolicy = hitPolicy;
    this.headers = headers;
  }

  public HitPolicy getHitPolicy() {
    return hitPolicy;
  }

  public List<String> getHeaders() {
    return headers;
  }
}
