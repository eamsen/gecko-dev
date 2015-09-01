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
  sessions = parseLog(log)

  if args.normalize:
    normalize(sessions)

  for session in sessions.itervalues():
    sortedModules = session.modules.values()
    if args.sort:
      sortedModules = sorted(sortedModules, key=lambda m: m.stats.normalized, reverse=True)
    if args.top:
      sortedModules = sortedModules[:args.top]
    if args.stats:
      print '\n\n'.join([str(m) for m in sortedModules])
    if args.modules:
      print 'duration format [ms] = [thread, process, real]'
      print 'session=%s phase=%s' % (session.id, session.phase)
      print "%65s\t%28s\t%28s\t%28s\t%8s\t%8s\t%8s" % ('module', 'normalized max', 'max', 'total', 'parents', 'children', 'imports')
      print '\n'.join(["%65s\t%28s\t%28s\t%28s\t%8d\t%8d\t%8d" %
          (shorten(m.name), m.stats.normalized, m.stats.maxDuration,
           m.stats.totalDuration, len(m.uniqueParents()),
           len(m.uniqueChildren()), len(m.stats.durations))
          for m in sortedModules])

  sessionStats = summarize(sessions)
  print '\nsession stats (thread times [ms])'
  print '%20s\t%10s\t%10s\t%10s\t%10s\t%10s\t%20s' % ('phase', 'count', 'min', 'max', 'median', 'average', 'standard deviation')
  print '\n'.join(['%20s\t%10d\t%10d\t%10d\t%10d\t%10d\t%13d [%3d%%]' %
        (stats['phase'], stats['num'], stats['min'], stats['max'], stats['median'],
         stats['average'], stats['deviation'],
         100.0 * stats['deviation'] / stats['average'])
        for stats in sessionStats.itervalues()])
  

def summarize(sessions):
  def sortKey(session):
    return session.totalDuration().thread

  phases = dict([(s.phase, {'phase': s.phase}) for s in sessions.itervalues()])
  for phaseName in phases.iterkeys():
    phaseSessions = filter(lambda s: s.phase == phaseName, sessions.itervalues())
    minDuration = min(phaseSessions, key=sortKey).totalDuration().thread
    maxDuration = max(phaseSessions, key=sortKey).totalDuration().thread
    sortedDurations = sorted(phaseSessions, key=sortKey)
    totalDuration = sum([s.totalDuration().thread for s in sortedDurations])
    numSessions = len(phaseSessions)
    averageDuration = totalDuration / numSessions
    medianDuration = sortedDurations[numSessions / 2].totalDuration().thread
    deviation = math.sqrt(sum([math.pow(s.totalDuration().thread - averageDuration, 2) for s in phaseSessions]) / numSessions)
    phases[phaseName] = { 'phase': phaseName,
            'min': minDuration, 'max': maxDuration, 'total': totalDuration,
            'average': averageDuration, 'median': medianDuration,
            'num': numSessions, 'deviation': deviation }
  return phases

def shorten(name):
  if name.find("jar:jar") == -1:
    return name
  return name[:name.find(":")] + ":..." + name[name.rfind("/"):]


def normalize(sessions):
  for session in sessions.itervalues():
    for module in session.modules.itervalues():
      for child in module.children:
        module.stats.normalized.sub(child[1])
        assert module.stats.normalized.thread >= 0 or module.name == 'root'
    

def parseLog(log):
  sessions = defaultdict(Session)
  for line in log.splitlines():
    parseLine(sessions, line)
  return sessions


def parseLine(sessions, line):
  start = line.find('rabbit')
  if start != -1:
    start += len('rabbit') + 1
    line = line[start:]
  columns = line.split('\t')
  if len(columns) != 8:
    return
  phase, stackSize, parent, name, thread, process, real, sessionId = columns
  duration = Duration(float(thread), float(process), float(real))
  sessionId += '-' + phase
  session = sessions[sessionId]
  session.id = sessionId
  session.phase = phase
  module = session.modules[name]
  module.name = name
  module.parents.append(parent)
  module.stats.update(Duration(float(thread), float(process), float(real)))

  parentModule = session.modules[parent]
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
  parser.add_argument('--modules', action='store_true', help='show module results')
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


class Session:

  def __init__(self, id=0, phase="none"):
    self.id = id
    self.phase = phase
    self.modules = defaultdict(Module)

  def totalDuration(self):
    total = Duration(0, 0, 0)
    for module in self.modules.itervalues():
      if module.name == 'root':
        continue
      total.add(module.stats.normalized)
    return total


if __name__ == '__main__':
  main()
