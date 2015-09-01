#!/usr/bin/python

import argparse
import os
import subprocess
import math

from collections import defaultdict
from copy import copy

args = None


def main():
  global args

  args = parse_args()
  log = open(args.path).read()
  modules = parseLog(log)

  if args.normalize:
    normalize(modules)

  sortedModules = modules.values()

  if args.sort:
    sortedModules = sorted(sortedModules, key=lambda m: m.stats.normalized, reverse=True)
  if args.top:
    sortedModules = sortedModules[:args.top]
  if args.stats:
    print '\n\n'.join([str(m) for m in sortedModules])
  else:
    print 'duration format [ms] = [thread, process, real]'
    print "%65s\t%28s\t%28s\t%28s\t%8s\t%8s\t%8s" % ('module', 'normalized max', 'max', 'total', 'parents', 'children', 'imports')
    print '\n'.join(["%65s\t%28s\t%28s\t%28s\t%8d\t%8d\t%8d" % (m.name, m.stats.normalized, m.stats.maxDuration, m.stats.totalDuration, len(m.uniqueParents()), len(m.uniqueChildren()), len(m.stats.durations)) for m in sortedModules])


def normalize(modules):
  for module in modules.itervalues():
    for child in module.children:
      module.stats.normalized.sub(child[1])
      assert module.stats.normalized.thread >= 0 or module.name == 'root'
    

def parseLog(log):
  modules = defaultdict(Module)
  for line in log.splitlines():
    parseLine(modules, line)
  return modules


def parseLine(modules, line):
  start = line.find('rabbit')
  if start != -1:
    start += len('rabbit') + 1
    line = line[start:]
  columns = line.split('\t')
  if len(columns) != 6:
    return
  stackSize, parent, name, thread, process, real = columns
  duration = Duration(float(thread), float(process), float(real))
  module = modules[name]
  module.name = name
  module.parents.append(parent)
  module.stats.update(Duration(float(thread), float(process), float(real)))

  parentModule = modules[parent]
  parentModule.name = parent
  parentModule.children.append((name, duration))



def parse_args():
  global args

  parser = argparse.ArgumentParser(description='')
  parser.add_argument('path', help='path')
  parser.add_argument('--top', type=int, default=0, help='output top n')
  parser.add_argument('--no-sort', dest='noSort', action='store_true',
                      help='do not sort by (max/normalized) duration')
  parser.add_argument('--no-normalize', dest='noNormalize', action='store_true',
                      help='keep absolute import durations')
  parser.add_argument('--stats', action='store_true', help='show full stats')
  args = parser.parse_args()
  args.sort = not args.noSort
  args.normalize = not args.noNormalize
  return args
  

class Duration:

  def __init__(self, thread, process, real):
    self.thread = thread
    self.process = process
    self.real = real

  def add(self, duration):
    self.thread += duration.thread
    self.process += duration.process
    self.real += duration.real

  def sub(self, duration):
    self.thread -= duration.thread
    self.process -= duration.process
    self.real -= duration.real

  def __cmp__(self, other):
    return cmp(self.thread, other.thread)

  def __str__(self):
    return "[%.2f | %.2f | %.2f]" % (self.thread, self.process, self.real)


class Stats:

  def __init__(self):
    self.totalDuration = Duration(0.0, 0.0, 0.0)
    self.durations = []
    self.maxDuration = Duration(0.0, 0.0, 0.0)
    self.normalized = Duration(0.0, 0.0, 0.0)

  def update(self, duration):
    self.totalDuration.add(duration)
    self.durations.append(duration)
    self.maxDuration = max(self.maxDuration, duration)
    self.normalized = copy(self.maxDuration)

  def __str__(self):
    return "total=%s | norm=%s | max=%s | durations=%s" % (self.totalDuration, self.normalized, self.maxDuration, [str(e) for e in self.durations]) 
  

class Module:

  def __init__(self):
    self.name = 'undefined'
    self.parents = []
    self.children = []
    self.stats = Stats()

  def uniqueChildren(self):
    return set([name for name, duration in self.children])

  def uniqueParents(self):
    return set(self.parents)

  def __str__(self):
    return "[name=%s | stats=[%s] | parents=%s | children=%s]" % (self.name, str(self.stats), [str(e) for e in self.uniqueParents()], [str(e) for e in self.uniqueChildren()])


if __name__ == '__main__':
  main()
