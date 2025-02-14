/* Any copyright is dedicated to the Public Domain.
   http://creativecommons.org/publicdomain/zero/1.0/ */

/**
 * Test inverting CensusTreeNode with a by alloaction stack breakdown.
 */

function run_test() {
  const BREAKDOWN = {
    by: "allocationStack",
    then: { by: "count", count: true, bytes: true },
    noStack: { by: "count", count: true, bytes: true },
  };

  let stack1, stack2, stack3, stack4;

  function a(n) {
    return b(n);
  }
  function b(n) {
    return c(n);
  }
  function c(n) {
    return saveStack(n);
  }
  function d(n) {
    return b(n);
  }
  function e(n) {
    return c(n);
  }

  const abc_Stack = a(3);
  const  bc_Stack = b(2);
  const   c_Stack = c(1);
  const dbc_Stack = d(3);
  const  ec_Stack = e(2);

  const REPORT = new Map([
    [abc_Stack, { bytes: 10, count: 1 }],
    [ bc_Stack, { bytes: 10, count: 1 }],
    [  c_Stack, { bytes: 10, count: 1 }],
    [dbc_Stack, { bytes: 10, count: 1 }],
    [ ec_Stack, { bytes: 10, count: 1 }],
    ["noStack", { bytes: 50, count: 5 }],
  ]);

  const EXPECTED = {
    name: null,
    bytes: 0,
    totalBytes: 0,
    count: 0,
    totalCount: 0,
    children: [
      {
        name: "noStack",
        bytes: 50,
        totalBytes: 50,
        count: 5,
        totalCount: 5,
        children: [
          {
            name: null,
            bytes: 0,
            totalBytes: 100,
            count: 0,
            totalCount: 10,
            children: undefined
          }
        ]
      },
      {
        name: abc_Stack,
        bytes: 50,
        totalBytes: 50,
        count: 5,
        totalCount: 5,
        children: [
          {
            name: null,
            bytes: 0,
            totalBytes: 100,
            count: 0,
            totalCount: 10,
            children: undefined
          },
          {
            name: abc_Stack.parent,
            bytes: 0,
            totalBytes: 30,
            count: 0,
            totalCount: 3,
            children: [
              {
                name: null,
                bytes: 0,
                totalBytes: 100,
                count: 0,
                totalCount: 10,
                children: undefined
              },
              {
                name: abc_Stack.parent.parent,
                bytes: 0,
                totalBytes: 10,
                count: 0,
                totalCount: 1,
                children: [
                  {
                    name: null,
                    bytes: 0,
                    totalBytes: 100,
                    count: 0,
                    totalCount: 10,
                    children: undefined
                  }
                ]
              },
              {
                name: dbc_Stack.parent.parent,
                bytes: 0,
                totalBytes: 10,
                count: 0,
                totalCount: 1,
                children: [
                  {
                    name: null,
                    bytes: 0,
                    totalBytes: 100,
                    count: 0,
                    totalCount: 10,
                    children: undefined
                  }
                ]
              }
            ]
          },
          {
            name: ec_Stack.parent,
            bytes: 0,
            totalBytes: 10,
            count: 0,
            totalCount: 1,
            children: [
              {
                name: null,
                bytes: 0,
                totalBytes: 100,
                count: 0,
                totalCount: 10,
                children: undefined
              }
            ]
          }
        ]
      }
    ]
  };

  compareCensusViewData(BREAKDOWN, REPORT, EXPECTED, { invert: true });
}
